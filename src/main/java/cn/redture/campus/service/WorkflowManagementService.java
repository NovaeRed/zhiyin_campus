package cn.redture.campus.service;

import cn.redture.campus.exception.BaseException;
import cn.redture.campus.pojo.entity.User;
import cn.redture.campus.util.SecurityContextHolderUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class WorkflowManagementService {
    private final JdbcTemplate db; private final AccountAccessService accounts; private final PortalService portal;
    private final OperationAuditService audit; private final ObjectMapper json;
    public WorkflowManagementService(JdbcTemplate db,AccountAccessService accounts,PortalService portal,OperationAuditService audit,ObjectMapper json) {
        this.db=db;this.accounts=accounts;this.portal=portal;this.audit=audit;this.json=json;
    }
    private long uid(){return accounts.requireActive(SecurityContextHolderUtil.getUserId()).getId();}
    public User administrator() {
        var user=accounts.requireActive(SecurityContextHolderUtil.getUserId());
        if(!Set.of("admin","super_admin").contains(user.getRole())) fail(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED","仅管理员可执行此操作");
        return user;
    }
    public List<Map<String,Object>> users(int page,int size) {
        administrator(); page(page,size);
        return db.queryForList("SELECT id,student_no,username,role,is_active,created_at FROM users WHERE deleted_at IS NULL ORDER BY id LIMIT ? OFFSET ?",size,(page-1)*size);
    }
    @Transactional
    public void updateUser(long id,String role,Boolean enabled) {
        var actor=administrator();
        db.queryForObject("SELECT pg_advisory_xact_lock(629781) IS NULL",Boolean.class);
        actor=administrator();
        var rows=db.queryForList("SELECT role,is_active FROM users WHERE id=? AND deleted_at IS NULL FOR UPDATE",id);
        if(rows.isEmpty()) missing(); var target=rows.getFirst();
        if(role==null&&enabled==null) invalid();
        if(role!=null&&!Set.of("student","editor","admin","super_admin").contains(role)) invalid();
        if((Set.of("admin","super_admin").contains(target.get("role"))||Set.of("admin","super_admin").contains(role==null?"":role))&&!actor.getRole().equals("super_admin"))
            fail(HttpStatus.FORBIDDEN,"SUPER_ADMIN_REQUIRED","管理员权限的调整仅限超级管理员");
        if(actor.getId()==id && (Boolean.FALSE.equals(enabled)||role!=null&&!role.equals(actor.getRole())))
            fail(HttpStatus.CONFLICT,"SELF_ADMIN_CHANGE","不可停用或降级自己的管理账号");
        db.update("UPDATE users SET role=COALESCE(?,role),is_active=COALESCE(?,is_active),updated_at=now() WHERE id=?",role,enabled,id);
        var detail=new LinkedHashMap<String,Object>();detail.put("previous",target);detail.put("role",role);detail.put("active",enabled);
        audit.record("USER_UPDATE","user",id,detail);
    }
    public Map<String,Object> profile(){ var rows=db.queryForList("SELECT major,grade,target_cohort FROM student_profiles WHERE user_id=?",uid()); return rows.isEmpty()?Map.of():rows.getFirst(); }
    @Transactional
    public void profile(String major,Integer grade,String cohort) {
        var user=accounts.requireActive(uid()); validateScope(major,grade,cohort);
        if(major==null||major.isBlank()||grade==null) invalid();
        db.update("INSERT INTO student_profiles(user_id,student_no,major,grade,target_cohort) VALUES (?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET major=EXCLUDED.major,grade=EXCLUDED.grade,target_cohort=EXCLUDED.target_cohort,synced_at=now()",user.getId(),user.getStudentNo(),major.trim(),grade,cohort);
    }
    @Transactional
    public void session(long id,String title,Boolean archived,String major,Integer grade,String cohort,boolean setScope) {
        long user=uid();
        if(db.queryForList("SELECT id FROM rag_sessions WHERE id=? AND user_id=? AND target_scope->>'deletedAt' IS NULL FOR UPDATE",id,user).isEmpty()) missing();
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_rag_requests WHERE session_id=? AND state='running')",Boolean.class,id)))
            fail(HttpStatus.CONFLICT,"SESSION_BUSY","请在当前问答完成后修改会话");
        if(title!=null&&(title.isBlank()||title.length()>120)) invalid();
        db.update("UPDATE rag_sessions SET title=COALESCE(?,title),archived=COALESCE(?,archived),updated_at=now() WHERE id=? AND user_id=?",title,archived,id,user);
        if(setScope) {
            validateScope(major,grade,cohort); var scope=new LinkedHashMap<String,Object>();
            if(major!=null&&!major.isBlank())scope.put("major",major.trim());if(grade!=null)scope.put("grade",grade);if(cohort!=null)scope.put("cohort",cohort);
            db.update("UPDATE rag_sessions SET target_scope=?::jsonb WHERE id=?",write(scope),id);
        }
    }
    @Transactional
    public int deleteSessions(List<Long> ids) {
        long user=uid();
        if(ids==null||ids.isEmpty()||ids.size()>100||ids.stream().anyMatch(id -> id==null||id<=0)) invalid();
        var unique=new TreeSet<>(ids);
        String placeholders=String.join(",",Collections.nCopies(unique.size(),"?"));
        var parameters=new ArrayList<Object>(); parameters.add(user); parameters.addAll(unique);
        // Match claim/update row locking: a new question cannot slip between the busy check and deletion.
        var sessions=db.queryForList("SELECT id FROM rag_sessions WHERE user_id=? AND id IN ("+placeholders+") ORDER BY id FOR UPDATE",parameters.toArray());
        if(sessions.size()!=unique.size()) missing();
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_rag_requests WHERE state='running' AND session_id IN ("+placeholders+"))",Boolean.class,unique.toArray())))
            fail(HttpStatus.CONFLICT,"SESSION_BUSY","选中的对话仍在回答中，请等待完成或取消后再删除");
        int deleted=db.update("UPDATE rag_sessions SET archived=true,target_scope=target_scope||jsonb_build_object('deletedAt',now()),updated_at=now() WHERE user_id=? AND id IN ("+placeholders+") AND target_scope->>'deletedAt' IS NULL",parameters.toArray());
        if(deleted>0) audit.record("SESSION_DELETE","session",unique.first(),Map.of("ids",unique,"count",deleted));
        return deleted;
    }
    public List<Map<String,Object>> archived(int page,int size) {page(page,size);return db.queryForList("SELECT id,title,archived,target_scope,updated_at FROM rag_sessions WHERE user_id=? AND archived AND target_scope->>'deletedAt' IS NULL ORDER BY updated_at DESC LIMIT ? OFFSET ?",uid(),size,(page-1)*size);}
    public Map<String,Object> scope(long id) {
        var rows=db.queryForList("SELECT id,title,archived,target_scope FROM rag_sessions WHERE id=? AND user_id=? AND target_scope->>'deletedAt' IS NULL",id,uid());if(rows.isEmpty())missing();return rows.getFirst();
    }
    @Transactional
    public void feedback(long query,int rating,String comment) {
        long user=uid(); if((rating!=1&&rating!=-1)||comment==null||comment.length()>2000)invalid();
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM rag_queries q JOIN rag_sessions s ON s.id=q.session_id WHERE q.id=? AND s.user_id=? AND s.target_scope->>'deletedAt' IS NULL AND q.workflow_state='completed')",Boolean.class,query,user))) missing();
        db.update("INSERT INTO app_rag_feedback(query_id,user_id,rating,comment) VALUES (?,?,?,?) ON CONFLICT(query_id,user_id) DO UPDATE SET rating=EXCLUDED.rating,comment=EXCLUDED.comment,updated_at=now()",query,user,rating,comment);
    }
    public List<Map<String,Object>> audits(int page,int size){administrator();page(page,size);return db.queryForList("SELECT id,actor_id,action,object_type,object_id,detail,created_at FROM app_audit_events ORDER BY id DESC LIMIT ? OFFSET ?",size,(page-1)*size);}
    @Transactional
    public void editDocument(long id,String title,String version) {
        portal.requireManager(); if(title==null||title.isBlank()||title.length()>255||version==null||version.isBlank()||version.length()>32) invalid();
        var before=portal.document(id);
        if(db.update("UPDATE knowledge_docs SET title=?,version=?,updated_at=now() WHERE id=? AND status IN ('active','deprecated')",title.trim(),version.trim(),id)!=1)
            fail(HttpStatus.CONFLICT,"DOCUMENT_BUSY","仅可编辑已完成入库的文档；正文或切片变更请执行版本重建");
        audit.record("DOCUMENT_EDIT","document",id,Map.of("previousTitle",before.getTitle(),"title",title,"version",version));
    }
    private void validateScope(String major,Integer grade,String cohort) {
        if(major!=null&&major.length()>100||grade!=null&&(grade<1900||grade>2200)||cohort!=null&&!cohort.matches("20\\d{2}届"))invalid();
    }
    private void page(int page,int size){if(page<1||page>100000||size<1||size>100)invalid();}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private void missing(){fail(HttpStatus.NOT_FOUND,"NOT_FOUND","记录不存在或无权访问");}
    private void invalid(){fail(HttpStatus.BAD_REQUEST,"INVALID_INPUT","请求参数不合法");}
    private void fail(HttpStatus status,String code,String message){throw new BaseException(status,message,code);}
}
