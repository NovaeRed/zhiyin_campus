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
 * 模型调用前先提交持久化请求声明，失败的请求不隐式重放。
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
        // 限制每轮历史长度，避免长课程清单挤掉最新的追问上下文。
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

    private void saveContext(long session, RagAskRequestDTO request, RagAnswerResponseVO response) {
        var plan = request.getQueryIntent();
        if (plan == null) {
            return;
        }
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

    @org.springframework.transaction.annotation.Transactional
    public void cancel(String key) {
        long user = SecurityContextHolderUtil.getUserId();
        var rows = reliableRagMapper.cancelRequest(user, key);
        if (rows.isEmpty()) {
            status(key);
            return;
        }
        var row = rows.getFirst();
        // 请求可能尚未关联查询；保持原 JDBC 对空查询 ID 不执行更新的行为。
        if (row.get("query_id") instanceof Number queryId) {
            reliableRagMapper.cancelQuery(queryId.longValue());
        }
        var thread = active.get((UUID) row.get("id"));
        if (thread != null) {
            thread.interrupt();
        }
    }

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
