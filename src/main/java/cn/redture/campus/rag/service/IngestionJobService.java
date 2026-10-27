package cn.redture.campus.rag.service;

import cn.redture.campus.config.RagProperties;
import cn.redture.campus.exception.BaseException;
import cn.redture.campus.mapper.IngestionJobMapper;
import cn.redture.campus.pojo.dto.DocumentUploadRequestDTO;
import cn.redture.campus.pojo.dto.UserPrincipal;
import cn.redture.campus.pojo.vo.DocumentUploadResponseVO;
import cn.redture.campus.rag.repository.KnowledgeRepository;
import cn.redture.campus.service.AccountAccessService;
import cn.redture.campus.service.OperationAuditService;
import cn.redture.campus.service.PortalService;
import cn.redture.campus.util.SecurityContextHolderUtil;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Service
public class IngestionJobService {
    private final IngestionJobMapper ingestionJobMapper;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final PortalService portal;
    private final AccountAccessService accounts;
    private final OperationAuditService audit;
    private final DocumentIngestionService ingestion;
    private final KnowledgeRepository repository;
    private final Path directory;
    private final UUID instance = UUID.randomUUID();
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Semaphore slots = new Semaphore(2);
    private final Map<UUID, Thread> active = new ConcurrentHashMap<>();

    public IngestionJobService(
        IngestionJobMapper ingestionJobMapper,
        TransactionTemplate tx,
        ObjectMapper json,
        PortalService portal,
        AccountAccessService accounts,
        OperationAuditService audit,
        DocumentIngestionService ingestion,
        KnowledgeRepository repository,
        RagProperties properties) {
        this.ingestionJobMapper = ingestionJobMapper;
        this.tx = tx;
        this.json = json;
        this.portal = portal;
        this.accounts = accounts;
        this.audit = audit;
        this.ingestion = ingestion;
        this.repository = repository;
        directory =
            Path.of(properties.getStorage().getLocalFileDir())
                .toAbsolutePath()
                .normalize()
                .resolve("jobs");
    }

    public Map<String, Object> submit(
        MultipartFile file, DocumentUploadRequestDTO request, Long rebuild) {
        portal.requireManager();
        String name = file == null ? null : file.getOriginalFilename();
        if (file == null
            || file.isEmpty()
            || file.getSize() > 20L * 1024 * 1024
            || name == null
            || name.length() > 255
            || !name.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|docx|xlsx|md|html|txt)$")) {
            throw new BaseException(HttpStatus.BAD_REQUEST, "文件为空、格式不支持或超过20MB", "INVALID_UPLOAD");
        }
        if (rebuild != null) {
            var previous = portal.document(rebuild);
            if (request.getVersion() == null
                || request.getVersion().isBlank()
                || request.getVersion().equals(previous.getVersion())) {
                throw new BaseException(
                    HttpStatus.BAD_REQUEST, "版本重建必须提供不同的新版本号", "NEW_VERSION_REQUIRED");
            }
        }
        UUID id = UUID.randomUUID();
        Path source = directory.resolve(id + ".upload");
        try {
            Files.createDirectories(directory);
            try (var input = file.getInputStream()) {
                Files.copy(input, source);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot stage uploaded file", failure);
        }
        long user = SecurityContextHolderUtil.getUserId();
        tx.executeWithoutResult(
            status -> {
                // 重建任务入队前与文档删除使用相同行锁，保证操作顺序一致。
                if (rebuild != null) {
                    var current = ingestionJobMapper.lockDocumentVersion(rebuild);
                    if (current.isEmpty()) {
                        throw new BaseException(HttpStatus.NOT_FOUND, "文档不存在或已删除", "NOT_FOUND");
                    }
                    if (request.getVersion().equals(current.getFirst().get("version"))) {
                        throw new BaseException(
                            HttpStatus.BAD_REQUEST,
                            "版本重建必须提供不同的新版本号",
                            "NEW_VERSION_REQUIRED");
                    }
                }
                ingestionJobMapper.insertJob(
                    id, user, source.toString(), name, write(request), rebuild);
                audit.record(
                    "INGESTION_SUBMIT",
                    "ingestion_job",
                    id,
                    Map.of("fileName", name, "rebuild", rebuild == null ? "" : rebuild));
            });
        return Map.of("jobId", id, "state", "queued", "progress", 0);
    }

    public Map<String, Object> status(UUID id) {
        var user = portal.currentUser();
        portal.requireManager();
        boolean admin = Set.of("admin", "super_admin").contains(user.getRole());
        var rows = ingestionJobMapper.selectStatus(id, user.getId(), admin);
        if (rows.isEmpty()) {
            throw new BaseException(HttpStatus.NOT_FOUND, "任务不存在或无权访问", "NOT_FOUND");
        }
        var result = rows.getFirst();
        if (result.get("result") != null) {
            result.put("result", read(result.get("result").toString(), Map.class));
        }
        return result;
    }

    @org.springframework.transaction.annotation.Transactional
    public void retry(UUID id, boolean acknowledged) {
        status(id);
        if (!acknowledged) {
            throw new BaseException(
                HttpStatus.BAD_REQUEST,
                "重试可能重新调用Embedding并产生费用，请明确确认",
                "MODEL_COST_ACK_REQUIRED");
        }
        if (ingestionJobMapper.retryJob(id) != 1) {
            throw new BaseException(HttpStatus.CONFLICT, "仅失败任务可重试，最多3次执行", "INVALID_JOB_RETRY");
        }
        audit.record("INGESTION_RETRY", "ingestion_job", id, Map.of("modelCostAcknowledged", true));
    }

    @org.springframework.transaction.annotation.Transactional
    public void cancel(UUID id) {
        status(id);
        ingestionJobMapper.cancelJob(id);
        var worker = active.get(id);
        if (worker != null) {
            worker.interrupt();
        }
        audit.record("INGESTION_CANCEL", "ingestion_job", id, Map.of());
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        if (!slots.tryAcquire()) {
            return;
        }
        Map<String, Object> row;
        try {
            row =
                tx.execute(
                    status -> {
                        var rows = ingestionJobMapper.lockNextQueuedJob();
                        if (rows.isEmpty()) {
                            return null;
                        }
                        var job = rows.getFirst();
                        ingestionJobMapper.startJob(instance, (UUID) job.get("id"));
                        return job;
                    });
        } catch (RuntimeException failure) {
            slots.release();
            throw failure;
        }
        if (row == null) {
            slots.release();
            return;
        }
        workers.submit(
            () -> {
                try {
                    execute(row);
                } finally {
                    slots.release();
                }
            });
    }

    private void execute(Map<String, Object> job) {
        UUID id = (UUID) job.get("id");
        long user = ((Number) job.get("user_id")).longValue();
        active.put(id, Thread.currentThread());
        DocumentUploadResponseVO result = null;
        try {
            var account = accounts.requireActive(user);
            SecurityContextHolder.getContext()
                .setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                        new UserPrincipal(
                            String.valueOf(user), account.getUsername(), ""),
                        null,
                        List.of()));
            portal.requireManager();
            Path source = Path.of(job.get("source_path").toString()).toAbsolutePath().normalize();
            if (!source.startsWith(directory)) {
                throw new IllegalStateException("Invalid staged source path");
            }
            result =
                ingestion.ingest(
                    new StagedFile(source, job.get("original_name").toString()),
                    read(job.get("request").toString(), DocumentUploadRequestDTO.class),
                    (phase, progress) -> {
                        accounts.requireActive(user);
                        portal.requireManager();
                        if (Thread.currentThread().isInterrupted()
                            || ingestionJobMapper.updateProgress(phase, progress, id)
                            != 1) {
                            throw new CancellationException();
                        }
                    },
                    false);
            var completed = result;
            tx.executeWithoutResult(
                status -> {
                    var current = ingestionJobMapper.lockState(id);
                    if (!"processing".equals(current)) {
                        throw new CancellationException();
                    }
                    accounts.requireActive(user);
                    portal.requireManager();
                    repository.updateDocumentStatus(completed.getDocId(), "active");
                    if (job.get("rebuild_of") != null) {
                        repository.updateDocumentStatus(
                            ((Number) job.get("rebuild_of")).longValue(), "deprecated");
                    }
                    completed.setStatus("active");
                    ingestionJobMapper.completeJob(write(completed), id);
                    audit.record(
                        "INGESTION_COMPLETE",
                        "document",
                        completed.getDocId(),
                        Map.of("jobId", id));
                });
        } catch (RuntimeException failure) {
            if (result != null) {
                repository.updateDocumentStatus(result.getDocId(), "failed");
            }
            ingestionJobMapper.failJob(
                failure instanceof CancellationException
                    ? "JOB_INTERRUPTED"
                    : "INGESTION_FAILED",
                id);
        } finally {
            active.remove(id);
            SecurityContextHolder.clearContext();
            Thread.interrupted();
        }
    }

    @Scheduled(fixedDelay = 10000)
    public void renew() {
        for (var id : active.keySet()) {
            ingestionJobMapper.renewLease(id, instance);
        }
        ingestionJobMapper.expireJobs();
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }

    private String write(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String v, Class<T> t) {
        try {
            return json.readValue(v, t);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record StagedFile(Path path, String filename) implements MultipartFile {
        public String getName() {
            return "file";
        }

        public String getOriginalFilename() {
            return filename;
        }

        public String getContentType() {
            return "application/octet-stream";
        }

        public boolean isEmpty() {
            return getSize() == 0;
        }

        public long getSize() {
            try {
                return Files.size(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public byte[] getBytes() throws IOException {
            return Files.readAllBytes(path);
        }

        public InputStream getInputStream() throws IOException {
            return Files.newInputStream(path);
        }

        public void transferTo(File destination) throws IOException {
            Files.copy(path, destination.toPath());
        }
    }
}
