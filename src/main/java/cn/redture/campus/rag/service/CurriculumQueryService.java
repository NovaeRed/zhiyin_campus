package cn.redture.campus.rag.service;

import cn.redture.campus.rag.repository.KnowledgeRepository;
import cn.redture.campus.rag.retrieval.CourseSchedule;
import cn.redture.campus.rag.retrieval.CurriculumDocument;
import cn.redture.campus.rag.retrieval.CurriculumMetadata;
import cn.redture.campus.rag.retrieval.QueryIntent;
import cn.redture.campus.rag.retrieval.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按结构化条件执行培养方案查询与多版本对比。
 */
@Service
public class CurriculumQueryService {
    private final KnowledgeRepository repository;

    public CurriculumQueryService(KnowledgeRepository repository) {
        this.repository = repository;
    }

    public record Result(String answer, List<RetrievedChunk> sources) {
    }

    /**
     * 按专业、入学年份和毕业届别匹配有效培养方案目录。
     *
     * @param target  已解析的单一学业范围
     * @param catalog 当前有效培养方案目录
     * @return 满足显式范围的文档列表，未找到时为空
     */
    public List<CurriculumDocument> candidates(QueryIntent.AcademicTarget target, List<CurriculumDocument> catalog) {
        return catalog.stream()
            .filter(d -> target.major() == null || target.major().equals(CurriculumMetadata.majorOf(d)))
            .filter(d -> target.enrollmentYear() == null || target.enrollmentYear().equals(d.getTargetGrade()))
            .filter(d -> target.graduationCohort() == null || d.getTargetCohort() == null || target.graduationCohort().equals(d.getTargetCohort()))
            .toList();
    }

    /**
     * 对课程清单或比较任务逐范围选择唯一方案并读取全量课程记录；缺必要条件时澄清，多版本结果合并时重编号引用。
     *
     * @param plan    包含一个或多个课程查询范围的语义计划
     * @param catalog 供版本选择使用的有效培养方案目录
     * @return 完整课程结果、澄清或拒答文本及对应真实来源
     */
    public Result execute(QueryIntent plan, List<CurriculumDocument> catalog) {
        var sources = new ArrayList<RetrievedChunk>();
        var answers = new ArrayList<String>();
        for (var target : plan.academicTargets()) {
            var candidates = candidates(target, catalog);
            if (candidates.isEmpty() && target.major() != null) {
                return new Result("根据现有资料无法回答。", List.of());
            }
            if (target.major() == null) {
                return new Result("请补充要查询的专业，以确定适用的培养方案。", List.of());
            }
            if (target.enrollmentYear() == null) {
                // 只使用原文明示的届别映射，不从毕业年份减去假定学制。
                candidates = candidates.stream().filter(d -> target.graduationCohort() != null
                    && target.graduationCohort().equals(d.getTargetCohort())).toList();
                if (candidates.isEmpty()) {
                    return new Result(target.graduationCohort() == null
                        ? "请补充入学年份，以确定适用的培养方案。"
                        : "已按" + target.graduationCohort() + "毕业理解。现有培养方案目录未明确该届别对应的入学年份，请补充入学年份。", List.of());
                }
            }
            if (candidates.size() != 1) {
                return new Result("该范围存在多份培养方案，请补充入学年份或文档版本。", List.of());
            }
            if (plan.semester() != null && plan.studyYear() == null) {
                return new Result("已确定培养方案，请补充要查询大几的学期课程。", List.of());
            }
            var doc = candidates.getFirst();
            var result = CourseSchedule.answer(doc.getTargetGrade(), plan.studyYear(), plan.semester(),
                plan.courseNature(), plan.countCourses(), repository.curriculumCourseChunks(doc.getDocId()));
            if (result.sources().isEmpty()) {
                return new Result(result.answer(), List.of());
            }
            int offset = sources.size();
            String adjusted = Pattern.compile("\\[(\\d+)\\]").matcher(result.answer())
                .replaceAll(match -> "[" + (Integer.parseInt(match.group(1)) + offset) + "]");
            answers.add("【" + doc.getTitle() + "】\n" + adjusted);
            sources.addAll(result.sources());
        }
        if (answers.isEmpty()) {
            return new Result("根据现有资料无法回答。", List.of());
        }
        return new Result(String.join("\n\n", answers), List.copyOf(sources));
    }
}
