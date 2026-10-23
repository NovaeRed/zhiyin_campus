package cn.redture.campus.rag.service;

import cn.redture.campus.config.RagProperties;
import cn.redture.campus.exception.BaseException;
import cn.redture.campus.pojo.dto.DocumentUploadRequestDTO;
import cn.redture.campus.pojo.dto.UserPrincipal;
import cn.redture.campus.pojo.vo.DocumentUploadResponseVO;
import cn.redture.campus.rag.repository.KnowledgeRepository;
import cn.redture.campus.service.*;
import cn.redture.campus.util.SecurityContextHolderUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class IngestionJobService {
    private final JdbcTemplate db; private final TransactionTemplate tx; private final ObjectMapper json;
    private final PortalService portal; private final AccountAccessService accounts; private final OperationAuditService audit;
    private final DocumentIngestionService ingestion; private final KnowledgeRepository repository; private final Path directory;
    private final UUID instance=UUID.randomUUID();
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final Semaphore slots=new Semaphore(2);
    private final Map<UUID,Thread> active=new ConcurrentHashMap<>();
    public IngestionJobService(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,PortalService portal,AccountAccessService accounts,
                               OperationAuditService audit,DocumentIngestionService ingestion,KnowledgeRepository repository,RagProperties properties) {
        this.db=db;this.tx=tx;this.json=json;this.portal=portal;this.accounts=accounts;this.audit=audit;this.ingestion=ingestion;this.repository=repository;
        directory=Path.of(properties.getStorage().getLocalFileDir()).toAbsolutePath().normalize().resolve("jobs");
    }
    public Map<String,Object> submit(MultipartFile file,DocumentUploadRequestDTO request,Long rebuild) {
        portal.requireManager();
        String name=file==null?null:file.getOriginalFilename();
        if(file==null||file.isEmpty()||file.getSize()>20L*1024*1024||name==null||name.length()>255||!name.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|docx|xlsx|md|html|txt)$"))
            throw new BaseException(HttpStatus.BAD_REQUEST,"文件为空、格式不支持或超过20MB","INVALID_UPLOAD");
        if(rebuild!=null) {
            var previous=portal.document(rebuild);
            if(request.getVersion()==null||request.getVersion().isBlank()||request.getVersion().equals(previous.getVersion()))
                throw new BaseException(HttpStatus.BAD_REQUEST,"版本重建必须提供不同的新版本号","NEW_VERSION_REQUIRED");
        }
        UUID id=UUID.randomUUID(); Path source=directory.resolve(id+".upload");
        try { Files.createDirectories(directory); try(var input=file.getInputStream()){Files.copy(input,source);} }
        catch(IOException failure){throw new IllegalStateException("Cannot stage uploaded file",failure);}
        long user=SecurityContextHolderUtil.getUserId();
        tx.executeWithoutResult(status -> {
            // Serialize rebuild submission with document deletion before publishing the queued job.
            if(rebuild!=null) {
                var current=db.queryForList("SELECT version FROM knowledge_docs WHERE id=? AND metadata->>'deleted_at' IS NULL FOR UPDATE",rebuild);
                if(current.isEmpty())throw new BaseException(HttpStatus.NOT_FOUND,"文档不存在或已删除","NOT_FOUND");
                if(request.getVersion().equals(current.getFirst().get("version")))throw new BaseException(HttpStatus.BAD_REQUEST,"版本重建必须提供不同的新版本号","NEW_VERSION_REQUIRED");
            }
            db.update("INSERT INTO app_ingestion_jobs(id,user_id,source_path,original_name,request,state,rebuild_of) VALUES (?,?,?,?,?::jsonb,'queued',?)",id,user,source.toString(),name,write(request),rebuild);
            audit.record("INGESTION_SUBMIT","ingestion_job",id,Map.of("fileName",name,"rebuild",rebuild==null?"":rebuild));
        });
        return Map.of("jobId",id,"state","queued","progress",0);
    }
    public Map<String,Object> status(UUID id) {
        var user=portal.currentUser(); portal.requireManager();
        boolean admin=Set.of("admin","super_admin").contains(user.getRole());
        var rows=db.queryForList("SELECT id,user_id,state,phase,progress,attempt,error_code,result,rebuild_of,created_at,updated_at FROM app_ingestion_jobs WHERE id=? AND (user_id=? OR ?)",id,user.getId(),admin);
        if(rows.isEmpty())throw new BaseException(HttpStatus.NOT_FOUND,"任务不存在或无权访问","NOT_FOUND");
        var result=rows.getFirst();if(result.get("result")!=null)result.put("result",read(result.get("result").toString(),Map.class));return result;
    }
    @org.springframework.transaction.annotation.Transactional
    public void retry(UUID id,boolean acknowledged) {
        status(id);
        if(!acknowledged)throw new BaseException(HttpStatus.BAD_REQUEST,"重试可能重新调用Embedding并产生费用，请明确确认","MODEL_COST_ACK_REQUIRED");
        if(db.update("UPDATE app_ingestion_jobs SET state='queued',phase='queued',progress=0,error_code=NULL,result=NULL,updated_at=now() WHERE id=? AND state='failed' AND attempt<3",id)!=1)
            throw new BaseException(HttpStatus.CONFLICT,"仅失败任务可重试，最多3次执行","INVALID_JOB_RETRY");
        audit.record("INGESTION_RETRY","ingestion_job",id,Map.of("modelCostAcknowledged",true));
    }
    @org.springframework.transaction.annotation.Transactional
    public void cancel(UUID id) {
        status(id);db.update("UPDATE app_ingestion_jobs SET state='cancelled',updated_at=now() WHERE id=? AND state IN ('queued','processing')",id);
        var worker=active.get(id);if(worker!=null)worker.interrupt();
        audit.record("INGESTION_CANCEL","ingestion_job",id,Map.of());
    }
    @Scheduled(fixedDelay=1000)
    public void dispatch() {
        if(!slots.tryAcquire())return;
        Map<String,Object> row;
        try {
            row=tx.execute(status -> {
                var rows=db.queryForList("SELECT * FROM app_ingestion_jobs WHERE state='queued' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1");
                if(rows.isEmpty())return null;var job=rows.getFirst();
                db.update("UPDATE app_ingestion_jobs SET state='processing',phase='starting',attempt=attempt+1,instance_id=?,lease_until=now()+interval '2 minutes',updated_at=now() WHERE id=?",instance,job.get("id"));return job;
            });
        } catch(RuntimeException failure){slots.release();throw failure;}
        if(row==null){slots.release();return;}
        workers.submit(()->{try{execute(row);}finally{slots.release();}});
    }
    private void execute(Map<String,Object> job) {
        UUID id=(UUID)job.get("id");long user=((Number)job.get("user_id")).longValue();active.put(id,Thread.currentThread());
        DocumentUploadResponseVO result=null;
        try {
            var account=accounts.requireActive(user);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new UserPrincipal(String.valueOf(user),account.getUsername(),""),null,List.of()));
            portal.requireManager();
            Path source=Path.of(job.get("source_path").toString()).toAbsolutePath().normalize();if(!source.startsWith(directory))throw new IllegalStateException("Invalid staged source path");
            result=ingestion.ingest(new StagedFile(source,job.get("original_name").toString()),read(job.get("request").toString(),DocumentUploadRequestDTO.class),(phase,progress)->{
                accounts.requireActive(user);portal.requireManager();
                if(Thread.currentThread().isInterrupted()||db.update("UPDATE app_ingestion_jobs SET phase=?,progress=?,updated_at=now() WHERE id=? AND state='processing'",phase,progress,id)!=1)throw new CancellationException();
            },false);
            var completed=result;
            tx.executeWithoutResult(status -> {
                var current=db.queryForObject("SELECT state FROM app_ingestion_jobs WHERE id=? FOR UPDATE",String.class,id);
                if(!"processing".equals(current))throw new CancellationException();
                accounts.requireActive(user);portal.requireManager();
                repository.updateDocumentStatus(completed.getDocId(),"active");
                if(job.get("rebuild_of")!=null)repository.updateDocumentStatus(((Number)job.get("rebuild_of")).longValue(),"deprecated");
                completed.setStatus("active");
                db.update("UPDATE app_ingestion_jobs SET state='completed',phase='completed',progress=100,result=?::jsonb,updated_at=now() WHERE id=?",write(completed),id);
                audit.record("INGESTION_COMPLETE","document",completed.getDocId(),Map.of("jobId",id));
            });
        } catch(RuntimeException failure) {
            if(result!=null)repository.updateDocumentStatus(result.getDocId(),"failed");
            db.update("UPDATE app_ingestion_jobs SET state='failed',error_code=?,updated_at=now() WHERE id=? AND state='processing'",failure instanceof CancellationException?"JOB_INTERRUPTED":"INGESTION_FAILED",id);
        } finally {active.remove(id);SecurityContextHolder.clearContext();Thread.interrupted();}
    }
    @Scheduled(fixedDelay=10000)
    public void renew() {
        for(var id:active.keySet())db.update("UPDATE app_ingestion_jobs SET lease_until=now()+interval '2 minutes' WHERE id=? AND state='processing' AND instance_id=?",id,instance);
        db.update("UPDATE app_ingestion_jobs SET state='failed',error_code='WORKER_INTERRUPTED',updated_at=now() WHERE state='processing' AND lease_until<now()");
    }
    @PreDestroy public void close(){workers.shutdownNow();}
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    private <T>T read(String v,Class<T> t){try{return json.readValue(v,t);}catch(Exception e){throw new IllegalStateException(e);}}
    private record StagedFile(Path path,String filename) implements MultipartFile {
        public String getName(){return "file";}public String getOriginalFilename(){return filename;}public String getContentType(){return "application/octet-stream";}
        public boolean isEmpty(){return getSize()==0;}public long getSize(){try{return Files.size(path);}catch(IOException e){throw new UncheckedIOException(e);}}
        public byte[] getBytes() throws IOException{return Files.readAllBytes(path);}public InputStream getInputStream() throws IOException{return Files.newInputStream(path);}
        public void transferTo(File destination)throws IOException{Files.copy(path,destination.toPath());}
    }
}
