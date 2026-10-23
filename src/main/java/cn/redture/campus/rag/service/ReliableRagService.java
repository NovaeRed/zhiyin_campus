package cn.redture.campus.rag.service;

import cn.redture.campus.exception.BaseException;
import cn.redture.campus.pojo.dto.RagAskRequestDTO;
import cn.redture.campus.pojo.vo.RagAnswerResponseVO;
import cn.redture.campus.rag.repository.KnowledgeRepository;
import cn.redture.campus.rag.retrieval.*;
import cn.redture.campus.service.AccountAccessService;
import cn.redture.campus.util.SecurityContextHolderUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Durable request claims are committed before any model call. Failed claims are never replayed implicitly. */
@Service
public class ReliableRagService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final KnowledgeRepository repository;
    private final RagQueryOrchestrator rag;
    private final AccountAccessService accounts;
    private final ObjectMapper json;
    private final UUID instance = UUID.randomUUID();
    private final Map<UUID,Thread> active = new ConcurrentHashMap<>();
    private final Map<UUID,java.time.Instant> started = new ConcurrentHashMap<>();
    public ReliableRagService(JdbcTemplate db, TransactionTemplate tx, KnowledgeRepository repository,
                              RagQueryOrchestrator rag, AccountAccessService accounts, ObjectMapper json) {
        this.db=db; this.tx=tx; this.repository=repository; this.rag=rag; this.accounts=accounts; this.json=json;
    }
    private record Claim(UUID id, Long session, KnowledgeRepository.QueryRecord query, String cached) { }

    public RagAnswerResponseVO ask(RagAskRequestDTO input) {
        long user=SecurityContextHolderUtil.getUserId(); accounts.requireActive(user);
        if(input.getQuestion()==null || input.getQuestion().isBlank() || input.getQuestion().length()>4000) bad("INVALID_QUESTION","问题长度应为1～4000字符");
        String key=input.getRequestId()==null ? UUID.randomUUID().toString() : input.getRequestId();
        if(!key.matches("[A-Za-z0-9._:-]{1,128}")) bad("INVALID_REQUEST_ID","requestId格式不合法");
        String fingerprint=hash(write(Arrays.asList(input.getQuestion(),input.getSessionId(),input.getTargetMajor(),input.getTargetGrade(),input.getTargetCohort())));
        Claim claim=tx.execute(status -> {
            UUID id=UUID.randomUUID();
            var inserted=db.queryForList("INSERT INTO app_rag_requests(id,user_id,request_key,request_hash,state,instance_id) VALUES (?,?,?,?,'running',?) ON CONFLICT(user_id,request_key) DO NOTHING RETURNING id",id,user,key,fingerprint,instance);
            if(inserted.isEmpty()) {
                var previous=db.queryForMap("SELECT * FROM app_rag_requests WHERE user_id=? AND request_key=? FOR UPDATE",user,key);
                if(previous.get("session_id")!=null && db.queryForList("SELECT id FROM rag_sessions WHERE id=? AND user_id=? AND target_scope->>'deletedAt' IS NULL FOR SHARE",previous.get("session_id"),user).isEmpty())
                    throw new BaseException(HttpStatus.NOT_FOUND,"会话不存在或已删除","SESSION_NOT_FOUND");
                if(!fingerprint.equals(previous.get("request_hash"))) conflict("IDEMPOTENCY_CONFLICT","同一requestId不能用于不同请求");
                if("completed".equals(previous.get("state"))) return new Claim((UUID)previous.get("id"),null,null,String.valueOf(previous.get("response")));
                conflict("REQUEST_"+previous.get("state").toString().toUpperCase(Locale.ROOT),"请求已在处理或终止；不会自动重复执行模型调用，请查询请求状态");
            }
            Long session=repository.ensureSession(user,input.getSessionId());
            db.queryForObject("SELECT pg_advisory_xact_lock(?) IS NULL",Boolean.class,session);
            if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_rag_requests WHERE session_id=? AND state='running')",Boolean.class,session)))
                conflict("SESSION_BUSY","该会话已有问题正在处理，请稍后再试");
            var query=repository.insertQuery(session,input.getQuestion(),input.getQuestion());
            db.update("UPDATE app_rag_requests SET session_id=?,query_id=? WHERE id=?",session,query.queryId(),id);
            return new Claim(id,session,query,null);
        });
        if(claim.cached()!=null) return read(claim.cached(),RagAnswerResponseVO.class);
        active.put(claim.id(),Thread.currentThread());
        started.put(claim.id(),java.time.Instant.now());
        try {
            RagAskRequestDTO request=contextualize(user,claim.session(),input);
            var response=rag.askPrepared(request,claim.session(),claim.query());
            response.setOriginalQuery(input.getQuestion());
            accounts.requireActive(user);
            tx.executeWithoutResult(status -> {
                int updated=db.update("UPDATE app_rag_requests SET state='completed',response=?::jsonb,updated_at=now() WHERE id=? AND state='running'",write(response),claim.id());
                if(updated!=1) conflict("REQUEST_CANCELLED","请求已取消，结果不会发布");
                if(!response.getSources().isEmpty()) saveContext(claim.session(),request,response);
            });
            return response;
        } catch(RuntimeException failure) {
            tx.executeWithoutResult(status -> {
                String code=failure instanceof BaseException b ? b.getErrorCode() : "RAG_EXECUTION_FAILED";
                db.update("UPDATE app_rag_requests SET state='failed',error_code=?,updated_at=now() WHERE id=? AND state='running'",code,claim.id());
                db.update("UPDATE rag_queries SET workflow_state=CASE WHEN EXISTS(SELECT 1 FROM app_rag_requests WHERE id=? AND state='cancelled') THEN 'cancelled' ELSE 'failed' END,error_code=?,completed_at=now() WHERE id=?",claim.id(),code,claim.query().queryId());
            });
            throw failure;
        } finally { active.remove(claim.id()); started.remove(claim.id()); Thread.interrupted(); }
    }

    @SuppressWarnings("unchecked")
    private RagAskRequestDTO contextualize(long user,long session,RagAskRequestDTO input) {
        var request=new RagAskRequestDTO(); org.springframework.beans.BeanUtils.copyProperties(input,request);
        var scope=read(db.queryForObject("SELECT target_scope::text FROM rag_sessions WHERE id=? AND user_id=?",String.class,session,user),Map.class);
        var profiles=db.queryForList("SELECT major,grade,target_cohort FROM student_profiles WHERE user_id=?",user);
        Map<String,Object> profile=profiles.isEmpty()?Map.of():profiles.getFirst();
        if(scope.get("studyYear") instanceof Number year) request.setAcademicStudyYear(year.intValue());
        if(scope.get("semester") instanceof Number semester) request.setAcademicSemester(semester.intValue());
        if(scope.get("courseNature") instanceof String nature) request.setAcademicCourseNature(nature);
        if(request.getTargetMajor()==null) request.setTargetMajor((String)scope.getOrDefault("major",profile.get("major")));
        if(request.getTargetGrade()==null) {
            Object grade=scope.getOrDefault("grade",profile.get("grade")); if(grade instanceof Number number) request.setTargetGrade(number.intValue());
        }
        if(request.getTargetCohort()==null) request.setTargetCohort((String)scope.getOrDefault("cohort",profile.get("target_cohort")));
        String question=input.getQuestion();
        if(question.matches("\\s*(?:那|那么|然后)?大[一二三四五](?:呢|的呢|呢？|呢\\?|？|\\?)?\\s*")) {
            String last=(String)scope.get("lastQuestion");
            if(last==null || !CourseSchedule.requested(last)) bad("FOLLOW_UP_AMBIGUOUS","请明确要查询该年级的哪类信息");
            var year=java.util.regex.Pattern.compile("大[一二三四五]").matcher(question); year.find();
            question="培养方案中"+year.group()+"要修什么课";
        }
        if(question.matches("(?s).*(它|这门课|该课程|这门课程|那门课).*")) {
            var names=(List<String>)scope.getOrDefault("courses",List.of());
            if(names.size()!=1) bad("FOLLOW_UP_AMBIGUOUS","请明确课程名称，上一轮无法确定唯一课程");
            question=question.replaceAll("这门课程|这门课|该课程|那门课|它",java.util.regex.Matcher.quoteReplacement(names.getFirst()));
        }
        request.setQuestion(question);
        var turns=db.queryForList("SELECT q.original_query,q.rewritten_query,g.generated_answer FROM rag_queries q JOIN generation_traces g ON g.query_id=q.id JOIN rag_sessions s ON s.id=q.session_id WHERE s.id=? AND s.user_id=? AND q.workflow_state='completed' ORDER BY q.id DESC LIMIT 3",session,user);
        Collections.reverse(turns);
        // Bound each turn so a long complete course list cannot evict the newest follow-up.
        var boundedTurns=new ArrayList<Map<String,String>>();
        for(var turn:turns) boundedTurns.add(Map.of("question",bounded(turn.get("original_query"),500),
                "resolvedQuestion",bounded(turn.get("rewritten_query"),500),"answer",bounded(turn.get("generated_answer"),800)));
        String history=write(boundedTurns);
        while(history.length()>6000) {
            var shorter=new ArrayList<Map<String,String>>();
            for(var turn:boundedTurns) {
                var values=new LinkedHashMap<String,String>();
                turn.forEach((key,value) -> values.put(key,bounded(value,Math.max(1,value.length()/2))));
                shorter.add(values);
            }
            boundedTurns=shorter; history=write(boundedTurns);
        }
        request.setConversationHistory(history);
        return request;
    }
    private String bounded(Object value,int maximum) {
        String text=value==null?"":value.toString(); return text.substring(0,Math.min(text.length(),maximum));
    }
    private void saveContext(long session,RagAskRequestDTO request,RagAnswerResponseVO response) {
        var scope=AcademicScope.resolve(request.getQuestion(),request.getTargetGrade(),request.getTargetCohort());
        String major=CurriculumRoute.effectiveMajor(request.getQuestion(),request.getTargetMajor(),repository.activeCurricula());
        var context=new LinkedHashMap<String,Object>();
        if(major!=null) context.put("major",major); if(scope.grade()!=null) context.put("grade",scope.grade()); if(scope.cohort()!=null) context.put("cohort",scope.cohort());
        if(request.getAcademicStudyYear()!=null) context.put("studyYear",request.getAcademicStudyYear());
        if(request.getAcademicSemester()!=null) context.put("semester",request.getAcademicSemester());
        if(request.getAcademicCourseNature()!=null) context.put("courseNature",request.getAcademicCourseNature());
        context.put("lastQuestion",request.getQuestion());
        var chunks=response.getSources().stream().map(source -> RetrievedChunk.builder().content(source.getContent()).build()).toList();
        var names=CourseEvidence.extract(chunks).stream().map(CourseEvidence.Fact::name).distinct().filter(request.getQuestion()::contains).toList();
        context.put("courses",names.stream().filter(name -> names.stream().noneMatch(other -> !name.equals(other)&&other.contains(name))).toList());
        db.update("UPDATE rag_sessions SET target_scope=?::jsonb,updated_at=now() WHERE id=?",write(context),session);
    }
    public Map<String,Object> status(String key) {
        var rows=db.queryForList("SELECT r.id,r.request_key,r.state,r.error_code,r.session_id,r.query_id,r.response,r.created_at,r.updated_at FROM app_rag_requests r LEFT JOIN rag_sessions s ON s.id=r.session_id WHERE r.user_id=? AND r.request_key=? AND (r.session_id IS NULL OR s.target_scope->>'deletedAt' IS NULL)",SecurityContextHolderUtil.getUserId(),key);
        if(rows.isEmpty()) throw new BaseException(HttpStatus.NOT_FOUND,"请求不存在","NOT_FOUND");
        var result=rows.getFirst(); if(result.get("response")!=null) result.put("response",read(result.get("response").toString(),Map.class)); return result;
    }
    @org.springframework.transaction.annotation.Transactional
    public void cancel(String key) {
        long user=SecurityContextHolderUtil.getUserId();
        var rows=db.queryForList("UPDATE app_rag_requests SET state='cancelled',updated_at=now() WHERE user_id=? AND request_key=? AND state='running' AND EXISTS(SELECT 1 FROM rag_sessions s WHERE s.id=app_rag_requests.session_id AND s.target_scope->>'deletedAt' IS NULL) RETURNING id,query_id",user,key);
        if(rows.isEmpty()) { status(key); return; }
        var row=rows.getFirst(); db.update("UPDATE rag_queries SET workflow_state='cancelled',completed_at=now() WHERE id=?",row.get("query_id"));
        var thread=active.get((UUID)row.get("id")); if(thread!=null) thread.interrupt();
    }
    @Scheduled(fixedDelay=10000)
    public void heartbeat() {
        for(var id:active.keySet()) {
            if(java.time.Duration.between(started.getOrDefault(id,java.time.Instant.now()),java.time.Instant.now()).getSeconds()>180) {
                db.update("UPDATE app_rag_requests SET state='failed',error_code='REQUEST_TIMEOUT',updated_at=now() WHERE id=? AND state='running'",id);
                db.update("UPDATE rag_queries SET workflow_state='failed',error_code='REQUEST_TIMEOUT',completed_at=now() WHERE id=(SELECT query_id FROM app_rag_requests WHERE id=?)",id);
                var thread=active.get(id);if(thread!=null)thread.interrupt();
            } else db.update("UPDATE app_rag_requests SET lease_until=now()+interval '2 minutes' WHERE id=? AND state='running' AND instance_id=?",id,instance);
        }
        tx.executeWithoutResult(status -> {
            var expired=db.queryForList("UPDATE app_rag_requests SET state='failed',error_code='WORKER_INTERRUPTED',updated_at=now() WHERE state='running' AND lease_until<now() RETURNING query_id");
            for(var row:expired) if(row.get("query_id")!=null) db.update("UPDATE rag_queries SET workflow_state='failed',error_code='WORKER_INTERRUPTED',completed_at=now() WHERE id=?",row.get("query_id"));
        });
    }
    private String write(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode workflow data",e);} }
    private <T> T read(String value,Class<T> type) { try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Cannot decode workflow data",e);} }
    private String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
    private void bad(String code,String message){throw new BaseException(HttpStatus.BAD_REQUEST,message,code);}
    private void conflict(String code,String message){throw new BaseException(HttpStatus.CONFLICT,message,code);}
}
