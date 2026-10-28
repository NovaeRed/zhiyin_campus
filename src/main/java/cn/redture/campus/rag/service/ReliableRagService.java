package cn.redture.campus.rag.service;

import cn.redture.campus.exception.BaseException;
import cn.redture.campus.mapper.ReliableRagMapper;
import cn.redture.campus.pojo.dto.RagAskRequestDTO;
import cn.redture.campus.pojo.vo.RagAnswerResponseVO;
import cn.redture.campus.rag.repository.KnowledgeRepository;
import cn.redture.campus.service.AccountAccessService;
import cn.redture.campus.util.SecurityContextHolderUtil;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理问答请求声明、状态流转、会话上下文和完成结果重放。
 */
@Service
public class ReliableRagService {
    private final ReliableRagMapper reliableRagMapper;
    private final TransactionTemplate tx;
    private final KnowledgeRepository repository;
    private final RagQueryOrchestrator rag;
    private final AccountAccessService accounts;
    private final ObjectMapper json;
    private final UUID instance = UUID.randomUUID();
    private final Map<UUID, Thread> active = new ConcurrentHashMap<>();
    private final Map<UUID, java.time.Instant> started = new ConcurrentHashMap<>();

    public ReliableRagService(
        ReliableRagMapper reliableRagMapper,
        TransactionTemplate tx,
        KnowledgeRepository repository,
        RagQueryOrchestrator rag,
        AccountAccessService accounts,
        ObjectMapper json) {
        this.reliableRagMapper = reliableRagMapper;
        this.tx = tx;
        this.repository = repository;
        this.rag = rag;
        this.accounts = accounts;
        this.json = json;
    }

    private record Claim(
        UUID id, Long session, KnowledgeRepository.QueryRecord query, String cached) {
    }

    /**
     * 以用户和请求键原子声明执行权，处理指纹冲突或完成重放；新请求在补全上下文后执行问答，并仅在运行态发布结果。
     *
     * @param input 未经会话补全的原始请求，其字段用于计算幂等指纹
     * @return 已完成请求的缓存结果，或本次成功发布的回答
     */
    public RagAnswerResponseVO ask(RagAskRequestDTO input) {
        long user = SecurityContextHolderUtil.getUserId();
        accounts.requireActive(user);
        if (input.getQuestion() == null
            || input.getQuestion().isBlank()
            || input.getQuestion().length() > 4000) {
            bad("INVALID_QUESTION", "问题长度应为1～4000字符");
        }
        String key =
            input.getRequestId() == null ? UUID.randomUUID().toString() : input.getRequestId();
        if (!key.matches("[A-Za-z0-9._:-]{1,128}")) {
            bad("INVALID_REQUEST_ID", "requestId格式不合法");
        }
        // 指纹只使用调用方原始参数；补全会话范围后再计算会使同一请求的重试发生冲突。
        String fingerprint =
            hash(
                write(
                    Arrays.asList(
                        input.getQuestion(),
                        input.getSessionId(),
                        input.getTargetMajor(),
                        input.getTargetGrade(),
                        input.getTargetCohort())));
        Claim claim =
            tx.execute(
                status -> {
                    UUID id = UUID.randomUUID();
                    var inserted =
                        reliableRagMapper.claimRequest(
                            id, user, key, fingerprint, instance);
                    if (inserted.isEmpty()) {
                        var previous = reliableRagMapper.lockRequest(user, key);
                        if (previous.get("session_id") != null
                            && reliableRagMapper
                            .lockVisibleSession(
                                ((Number) previous.get("session_id"))
                                    .longValue(),
                                user)
                            .isEmpty()) {
                            throw new BaseException(
                                HttpStatus.NOT_FOUND, "会话不存在或已删除", "SESSION_NOT_FOUND");
                        }
                        if (!fingerprint.equals(previous.get("request_hash"))) {
                            conflict("IDEMPOTENCY_CONFLICT", "同一requestId不能用于不同请求");
                        }
                        if ("completed".equals(previous.get("state"))) {
                            return new Claim(
                                (UUID) previous.get("id"),
                                null,
                                null,
                                String.valueOf(previous.get("response")));
                        }
                        conflict(
                            "REQUEST_"
                                + previous.get("state")
                                .toString()
                                .toUpperCase(Locale.ROOT),
                            "请求已在处理或终止；不会自动重复执行模型调用，请查询请求状态");
                    }
                    Long session = repository.ensureSession(user, input.getSessionId());
                    // 事务锁只保护检查与声明；事务结束后由持久化的运行状态阻止同会话并发。
                    reliableRagMapper.lockSession(session);
                    if (Boolean.TRUE.equals(reliableRagMapper.hasRunningRequest(session))) {
                        conflict("SESSION_BUSY", "该会话已有问题正在处理，请稍后再试");
                    }
                    var query =
                        repository.insertQuery(
                            session, input.getQuestion(), input.getQuestion());
                    reliableRagMapper.attachQuery(session, query.queryId(), id);
                    return new Claim(id, session, query, null);
                });
        if (claim.cached() != null) {
            return read(claim.cached(), RagAnswerResponseVO.class);
        }
        active.put(claim.id(), Thread.currentThread());
        started.put(claim.id(), java.time.Instant.now());
        try {
            RagAskRequestDTO request = contextualize(user, claim.session(), input);
            var response = rag.askPrepared(request, claim.session(), claim.query());
            response.setOriginalQuery(input.getQuestion());
            accounts.requireActive(user);
            tx.executeWithoutResult(
                status -> {
                    // 通过运行态条件更新竞争完成状态，决定本次回答是否可以发布。
                    int updated =
                        reliableRagMapper.completeRequest(write(response), claim.id());
                    if (updated != 1) {
                        conflict("REQUEST_CANCELLED", "请求已取消，结果不会发布");
                    }
                    saveContext(claim.session(), request, response);
                });
            return response;
        } catch (RuntimeException failure) {
            tx.executeWithoutResult(
                status -> {
                    String code =
                        failure instanceof BaseException b
                            ? b.getErrorCode()
                            : "RAG_EXECUTION_FAILED";
                    reliableRagMapper.failRequest(code, claim.id());
                    reliableRagMapper.failQuery(claim.id(), code, claim.query().queryId());
                });
            throw failure;
        } finally {
            active.remove(claim.id());
            started.remove(claim.id());
            Thread.interrupted();
        }
    }

    /**
     * 复制原始请求，用会话范围和用户画像补足缺失条件，并装配最近三条已完成问答的有界历史。
     *
     * @param user    当前已验证的用户标识
     * @param session 已验证归属的会话标识
     * @param input   保留原问题和显式范围的原始请求
     * @return 供本次语义规划使用的独立请求副本
     */
    @SuppressWarnings("unchecked")
    private RagAskRequestDTO contextualize(long user, long session, RagAskRequestDTO input) {
        var request = new RagAskRequestDTO();
        org.springframework.beans.BeanUtils.copyProperties(input, request);
        var scope = read(reliableRagMapper.selectScope(session, user), Map.class);
        var profiles = reliableRagMapper.selectProfile(user);
        Map<String, Object> profile = profiles.isEmpty() ? Map.of() : profiles.getFirst();
        if (scope.get("studyYear") instanceof Number year) {
            request.setAcademicStudyYear(year.intValue());
        }
        if (scope.get("semester") instanceof Number semester) {
            request.setAcademicSemester(semester.intValue());
        }
        if (scope.get("courseNature") instanceof String nature) {
            request.setAcademicCourseNature(nature);
        }
        if (request.getTargetMajor() == null) {
            request.setTargetMajor((String) scope.getOrDefault("major", profile.get("major")));
        }
        if (request.getTargetGrade() == null) {
            Object grade = scope.getOrDefault("grade", profile.get("grade"));
            if (grade instanceof Number number) {
                request.setTargetGrade(number.intValue());
            }
        }
        if (request.getTargetCohort() == null) {
            request.setTargetCohort(
                (String) scope.getOrDefault("cohort", profile.get("target_cohort")));
        }
        request.setConversationDomain((String) scope.get("domain"));
        var turns = reliableRagMapper.selectRecentTurns(session, user);
        Collections.reverse(turns);
        // 分别限制问题、检索表达和回答长度，再组合最近几轮历史。
        var boundedTurns = new ArrayList<Map<String, String>>();
        for (var turn : turns) {
            boundedTurns.add(
                Map.of(
                    "question",
                    bounded(turn.get("original_query"), 500),
                    "resolvedQuestion",
                    bounded(turn.get("rewritten_query"), 500),
                    "answer",
                    bounded(turn.get("generated_answer"), 800)));
        }
        String history = write(boundedTurns);
        while (history.length() > 6000) {
            var shorter = new ArrayList<Map<String, String>>();
            for (var turn : boundedTurns) {
                var values = new LinkedHashMap<String, String>();
                turn.forEach(
                    (key, value) ->
                        values.put(key, bounded(value, Math.max(1, value.length() / 2))));
                shorter.add(values);
            }
            boundedTurns = shorter;
            history = write(boundedTurns);
        }
        request.setConversationHistory(history);
        return request;
    }

    private String bounded(Object value, int maximum) {
        String text = value == null ? "" : value.toString();
        return text.substring(0, Math.min(text.length(), maximum));
    }

    /**
     * 在结果发布事务中保存本次计划和领域；仅单一课程范围保留课程条件，切换主题时不沿用旧课程范围。
     *
     * @param session  需要更新上下文的会话标识
     * @param request  带有本次语义计划的执行请求
     * @param response 已生成并准备发布的回答；当前上下文写入以请求中的计划为准
     */
    private void saveContext(long session, RagAskRequestDTO request, RagAnswerResponseVO response) {
        var plan = request.getQueryIntent();
        if (plan == null) {
            return;
        }
        // 按新计划重建会话上下文，仅为单一课程范围保留学业条件。
        var context = new LinkedHashMap<String, Object>();
        context.put("domain", plan.domain());
        context.put("lastQuestion", request.getQuestion());
        context.put("resolvedQuestion", plan.searchQuery());
        context.put("queryPlan", plan);
        if (plan.academic() && plan.academicTargets().size() == 1) {
            var target = plan.academicTargets().getFirst();
            if (target.major() != null) {
                context.put("major", target.major());
            }
            if (target.enrollmentYear() != null) {
                context.put("grade", target.enrollmentYear());
            }
            if (target.graduationCohort() != null) {
                context.put("cohort", target.graduationCohort());
            }
            if (plan.studyYear() != null) {
                context.put("studyYear", plan.studyYear());
            }
            if (plan.semester() != null) {
                context.put("semester", plan.semester());
            }
            context.put("courseNature", plan.courseNature());
        }
        reliableRagMapper.updateScope(write(context), session);
    }

    /**
     * 按当前用户及请求键读取可见请求状态，已保存的JSON回答转换为可直接返回的结构。
     *
     * @param key 用户级幂等请求键
     * @return 请求状态、关联会话与查询标识及可选已完成回答
     */
    public Map<String, Object> status(String key) {
        var rows = reliableRagMapper.selectStatus(SecurityContextHolderUtil.getUserId(), key);
        if (rows.isEmpty()) {
            throw new BaseException(HttpStatus.NOT_FOUND, "请求不存在", "NOT_FOUND");
        }
        var result = rows.getFirst();
        if (result.get("response") != null) {
            result.put("response", read(result.get("response").toString(), Map.class));
        }
        return result;
    }

    /**
     * 将当前用户的运行请求及关联查询标为取消，并中断本实例对应的执行线程。
     *
     * @param key 需要取消的用户级幂等请求键
     */
    @org.springframework.transaction.annotation.Transactional
    public void cancel(String key) {
        long user = SecurityContextHolderUtil.getUserId();
        var rows = reliableRagMapper.cancelRequest(user, key);
        if (rows.isEmpty()) {
            status(key);
            return;
        }
        var row = rows.getFirst();
        // 请求已关联查询时，同步更新查询的取消状态。
        if (row.get("query_id") instanceof Number queryId) {
            reliableRagMapper.cancelQuery(queryId.longValue());
        }
        var thread = active.get((UUID) row.get("id"));
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * 定期为本实例活跃请求续租，终止超时线程，并将租约过期请求标为失败。
     */
    @Scheduled(fixedDelay = 10000)
    public void heartbeat() {
        for (var id : active.keySet()) {
            if (java.time.Duration.between(
                    started.getOrDefault(id, java.time.Instant.now()),
                    java.time.Instant.now())
                .getSeconds()
                > 180) {
                reliableRagMapper.timeoutRequest(id);
                reliableRagMapper.timeoutQuery(id);
                var thread = active.get(id);
                if (thread != null) {
                    thread.interrupt();
                }
            } else {
                reliableRagMapper.renewLease(id, instance);
            }
        }
        tx.executeWithoutResult(
            status -> {
                var expired = reliableRagMapper.expireRequests();
                for (var row : expired) {
                    if (row.get("query_id") != null) {
                        reliableRagMapper.interruptQuery(
                            ((Number) row.get("query_id")).longValue());
                    }
                }
            });
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode workflow data", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decode workflow data", e);
        }
    }

    /**
     * 对固定顺序序列化的原请求内容计算SHA-256，供同键异参冲突检测使用。
     *
     * @param value 尚未补全会话上下文的请求字段序列化文本
     * @return 十六进制SHA-256请求指纹
     */
    private String hash(String value) {
        try {
            return HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void bad(String code, String message) {
        throw new BaseException(HttpStatus.BAD_REQUEST, message, code);
    }

    private void conflict(String code, String message) {
        throw new BaseException(HttpStatus.CONFLICT, message, code);
    }
}
