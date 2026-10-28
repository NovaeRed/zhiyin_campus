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

    /**
     * 校验管理权限与文件约束，暂存上传文件并持久化排队任务；重建任务入队前锁定旧文档版本。
     *
     * @param file    需要解析和入库的原始上传文件
     * @param request 标题、版本、切片策略及范围等入库参数
     * @param rebuild 旧文档标识，为 {@code null} 时表示普通新增
     * @return 新任务标识、排队状态和初始进度
     */
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

    /**
     * 验证当前管理用户对任务的可见性，读取持久状态并解析已完成结果。
     *
     * @param id 异步入库任务标识
     * @return 包含阶段、进度、执行次数和可选结果的任务信息
     */
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

    /**
     * 在明确确认模型费用后，将允许重试的失败任务重新排队；不接受其他状态或超过执行次数上限的任务。
     *
     * @param id           需要重新排队的失败任务标识
     * @param acknowledged 调用方是否明确确认再次Embedding可能产生费用
     */
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

    /**
     * 更新可访问任务的取消状态并尽力中断本实例工作线程，保留已产生的外部调用及审计事实。
     *
     * @param id 需要取消的异步入库任务标识
     */
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

    /**
     * 占用本实例执行槽，在短事务中跳过被锁行并领取排队任务，再交由工作线程执行；所有退出路径归还执行槽。
     */
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

    /**
     * 以任务所属用户身份执行解析和Embedding准备，再在发布事务中复核任务与权限，原子发布新版本、停用旧版本并完成任务。
     *
     * @param job 已领取的任务行，包含用户、暂存路径、入库参数和可选重建目标
     */
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
            // 解析、向量调用和准备写入已经发生；这里只将版本切换与任务完成放在同一事务。
            tx.executeWithoutResult(
                status -> {
                    var current = ingestionJobMapper.lockState(id);
                    // 持有任务行锁并确认执行态后，进入新版本发布流程。
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

    /**
     * 为本实例正在执行的入库任务续租，并将超出租约的任务收敛为失败，不重新调用Embedding。
     */
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
