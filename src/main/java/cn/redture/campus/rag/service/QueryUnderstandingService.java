package cn.redture.campus.rag.service;

import cn.redture.campus.config.RagProperties;
import cn.redture.campus.pojo.dto.RagAskRequestDTO;
import cn.redture.campus.rag.retrieval.CurriculumDocument;
import cn.redture.campus.rag.retrieval.QueryIntent;
import cn.redture.campus.rag.retrieval.QueryIntent.AcademicTarget;
import cn.redture.campus.rag.retrieval.QueryIntent.Task;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 生成语义查询计划，并在调用或解析失败时回退原问题检索。
 */
@Service
public class QueryUnderstandingService {
    private final ObjectProvider<ChatModel> models;
    private final ObjectMapper json;
    private final RagProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(16);

    public QueryUnderstandingService(ObjectProvider<ChatModel> models, ObjectMapper json, RagProperties properties) {
        this.models = models;
        this.json = json;
        this.properties = properties;
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    /**
     * 使用同一次模型调用生成领域、任务、检索表达及结构化条件；调用或解析失败时退回原问题通用检索，取消信号继续向上传播。
     *
     * @param request 包含当前原问题、请求范围和已确认上下文的请求
     * @param catalog 用于规范专业名称和识别适用文档的有效培养方案目录
     * @return 正常语义计划或标记为降级的通用检索计划
     */
    public QueryIntent understand(RagAskRequestDTO request, List<CurriculumDocument> catalog) {
        if (!properties.getRetrieval().isSemanticAcademicIntentEnabled()) {
            return QueryIntent.search(request.getQuestion());
        }
        try {
            String prompt = """
                你是通用校园知识库的查询规划器，只理解问题，不回答事实。只输出JSON。
                校园知识包括培养方案、招聘会、比赛通知、奖助政策等。领域由当前问题决定，不能因为出现专业、毕业、年级就判为培养方案。
                原问题优先于旧话题。历史只用于指代，不是事实证据；“它”可以指活动、政策、课程等，不能一律解释为课程。
                输出字段：
                domain：业务主题字符串，如 curriculum/recruitment/competition/general；允许其他新主题。
                task：SEARCH（普通文档问答）、COURSE_LIST（完整课程清单/数量）、COURSE_FACT（仅查询课程学分或安排）、CURRICULUM_COMPARE（比较不同培养方案）。涉及课程原理、教材或其他知识的混合问题使用SEARCH，不能只回答其中的学分部分。
                searchQuery：保留当前任务与约束的独立检索问题，消解有依据的指代；非课程问题不能追加培养方案词语。
                alternatives：可选的至多2个不同措辞的短检索问题，不得改变主体、年份和范围。
                hypotheticalText：可选的短假设性文档表达，仅用于向量召回，不得编造具体时间、金额或课程数值；不需要时填null。
                academicTargets：仅后三类任务填写数组，每项包含 major、majorMention、enrollmentYear、enrollmentMention、graduationCohort、graduationMention。
                mention必须逐字摘自当前原问题。模型可根据目录将专业简称规范化，但不能以“目录没有”为由换成另一个专业。
                “28届毕业”只表示2028届毕业，不能推算成2024级。“28年举办”表示事件年份，不是入学年。
                未明确入学年份时填null；已确认上下文由程序合并，不能因用户补充毕业届别就清除已有入学年。
                比较多个专业/年级时拆为多个academicTargets，不合并成一个版本。
                followUp：是否承接上一话题的boolean。明确切换到招聘/比赛时为false；“它在哪里举办”可承接活动。
                studyYear：仅课程查询的大几1～8整数或null；semester：仅课程查询的1/2或null。
                courseNature：课程查询的ALL/REQUIRED/ELECTIVE，其他任务可省略；countCourses：是否统计课程门数。
                courseNames：COURSE_FACT要查询的课程名称或课程号数组，区分理论课和实验课，结合指代消解填写；其他任务填空数组。
                不得从个人“还有多少课”推断已修课程；大三等相对年级不能凭当前日期推算。
                数据：原问题=%s
                已确认上下文[专业,入学年,毕业届,学年,学期,性质,上轮主题]=%s
                仅用于规范专业及确认文档适用对象的培养方案目录=%s
                有界历史=%s
                """.formatted(json.writeValueAsString(request.getQuestion()),
                json.writeValueAsString(Arrays.asList(request.getTargetMajor(), request.getTargetGrade(),
                    request.getTargetCohort(), request.getAcademicStudyYear(), request.getAcademicSemester(),
                    request.getAcademicCourseNature(), request.getConversationDomain())),
                json.writeValueAsString(catalog), request.getConversationHistory() == null ? "[]" : request.getConversationHistory());
            String output = interpret(prompt);
            int first = output.indexOf('{'), last = output.lastIndexOf('}');
            if (first < 0 || last < first) {
                throw new IllegalArgumentException("语义规划未返回JSON对象");
            }
            return parse(request, json.readTree(output.substring(first, last + 1)));
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("问题理解被取消");
        } catch (Exception unavailable) {
            // 规划失败时按原问题检索，并记录降级状态。
            return QueryIntent.search(request.getQuestion());
        }
    }

    /**
     * 解析模型JSON并合并有文本依据的本轮条件与已确认上下文；普通检索任务不继承课程限制，毕业届别不推算为入学年。
     *
     * @param request 提供原文片段校验和已确认范围的请求
     * @param value   模型输出的JSON对象
     * @return 包含条件来源、检索表达和课程实体的结构化计划
     */
    QueryIntent parse(RagAskRequestDTO request, JsonNode value) {
        Task task = Task.valueOf(value.path("task").asText());
        String query = text(value, "searchQuery");
        if (query == null || query.length() > 4000) {
            throw new IllegalArgumentException("检索问题格式不正确");
        }
        String domain = text(value, "domain");
        var alternatives = new ArrayList<String>();
        if (properties.getRetrieval().isQueryPlanningEnabled() && properties.getRetrieval().getMaxExpandedQueries() > 0 && value.path("alternatives").isArray()) {
            for (var alternative : value.path("alternatives")) {
                if (alternative.isTextual() && !alternative.asText().isBlank() && alternative.asText().length() <= 1000) {
                    alternatives.add(alternative.asText());
                }
                if (alternatives.size() >= Math.max(0, properties.getRetrieval().getMaxExpandedQueries())) {
                    break;
                }
            }
        }
        String hypothetical = properties.getRetrieval().isQueryPlanningEnabled() ? text(value, "hypotheticalText") : null;
        if (hypothetical != null && hypothetical.length() > 1500) {
            hypothetical = null;
        }
        if (task == Task.SEARCH) {
            // 普通检索计划使用独立问题和通用检索范围。
            return new QueryIntent(query, domain == null ? "general" : domain, task, List.of(), List.of(), null, null, "ALL", false, false, List.copyOf(alternatives), hypothetical);
        }
        var targets = new ArrayList<AcademicTarget>();
        boolean followUp = value.path("followUp").asBoolean(false);
        JsonNode items = value.path("academicTargets");
        if (!items.isArray() || items.size() > properties.getRetrieval().getMaxAcademicScopes()) {
            throw new IllegalArgumentException("学业查询范围格式不正确");
        }
        for (var target : items) {
            boolean majorStated = grounded(request.getQuestion(), text(target, "majorMention"));
            String major = majorStated ? text(target, "major") : request.getTargetMajor();
            Integer year = grounded(request.getQuestion(), text(target, "enrollmentMention"))
                && !text(target, "enrollmentMention").contains("届") ? number(target, "enrollmentYear", 1900, 2200) : null;
            String yearSource = year == null ? "unknown" : "question";
            String cohort = grounded(request.getQuestion(), text(target, "graduationMention")) ? text(target, "graduationCohort") : null;
            // 当前明确新专业时不继承旧专业的方案年份；同一主体允许保留已确认入学年。
            if (year == null && (!majorStated || java.util.Objects.equals(major, request.getTargetMajor()))) {
                boolean cohortConflict = cohort != null && request.getTargetCohort() != null && !cohort.equals(request.getTargetCohort());
                if (!cohortConflict) {
                    year = request.getTargetGrade();
                    yearSource = year == null ? "unknown" : "context";
                }
            }
            if (cohort == null && followUp) {
                cohort = request.getTargetCohort();
            }
            targets.add(new AcademicTarget(major, year, cohort, majorStated ? "question" : "context", yearSource));
        }
        if (targets.isEmpty()) {
            targets.add(new AcademicTarget(request.getTargetMajor(), request.getTargetGrade(), request.getTargetCohort(), "context", "context"));
        }
        Integer study = number(value, "studyYear", 1, 8), semester = number(value, "semester", 1, 2);
        if (followUp && "curriculum".equals(request.getConversationDomain())) {
            if (study == null) {
                study = request.getAcademicStudyYear();
            }
            if (semester == null) {
                semester = request.getAcademicSemester();
            }
        }
        String nature = text(value, "courseNature");
        if (nature == null) {
            nature = followUp ? request.getAcademicCourseNature() : "ALL";
        }
        if (!List.of("ALL", "REQUIRED", "ELECTIVE").contains(nature == null ? "" : nature)) {
            nature = "ALL";
        }
        var courseNames = new ArrayList<String>();
        if (task == Task.COURSE_FACT && value.path("courseNames").isArray()) {
            for (var course : value.path("courseNames")) {
                if (course.isTextual() && !course.asText().isBlank()) {
                    courseNames.add(course.asText().trim());
                }
            }
        }
        return new QueryIntent(query, "curriculum", task, List.copyOf(targets), courseNames.stream().distinct().toList(), study, semester, nature, value.path("countCourses").asBoolean(false), false, List.copyOf(alternatives), hypothetical);
    }

    /**
     * 在受并发容量限制的虚拟线程中调用理解模型，按配置等待并在退出时取消尚未结束的任务。
     *
     * @param prompt 包含任务契约、目录及有界上下文的理解提示
     * @return 模型返回的原始文本，尚未进行JSON解析
     */
    private String interpret(String prompt) throws Exception {
        var task = executor.submit(() -> {
            if (!capacity.tryAcquire()) {
                throw new IllegalStateException("语义规划并发已满");
            }
            try {
                return models.getObject().chat(prompt);
            } finally {
                capacity.release();
            }
        });
        try {
            return task.get(properties.getRetrieval().getSemanticAcademicIntentTimeoutSeconds(), TimeUnit.SECONDS);
        } finally {
            task.cancel(true);
        }
    }

    private static String text(JsonNode node, String key) {
        return node.path(key).isTextual() && !node.path(key).asText().isBlank() ? node.path(key).asText().trim() : null;
    }

    private static boolean grounded(String question, String mention) {
        return mention != null && question.contains(mention);
    }

    private static Integer number(JsonNode node, String key, int minimum, int maximum) {
        var value = node.path(key);
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= minimum && value.asInt() <= maximum ? value.asInt() : null;
    }
}
