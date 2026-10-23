package cn.redture.campus.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.redture.campus.util.SecurityContextHolderUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class OperationAuditService {
    private final JdbcTemplate db; private final ObjectMapper json;
    public OperationAuditService(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public void record(String action,String type,Object id,Object detail) {
        try { db.update("INSERT INTO app_audit_events(actor_id,action,object_type,object_id,detail) VALUES (?,?,?,?,?::jsonb)",
                SecurityContextHolderUtil.getUserId(),action,type,String.valueOf(id),json.writeValueAsString(detail)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
}
