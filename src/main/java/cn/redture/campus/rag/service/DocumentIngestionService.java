package cn.redture.campus.rag.service;

import cn.redture.campus.config.RagProperties;
import cn.redture.campus.pojo.dto.DocumentUploadRequestDTO;
import cn.redture.campus.pojo.vo.DocumentUploadResponseVO;
import cn.redture.campus.rag.chunk.ChunkUnit;
import cn.redture.campus.rag.chunk.ChunkerRouter;
import cn.redture.campus.rag.chunk.DocumentChunker;
import cn.redture.campus.rag.chunk.TableRowBlocks;
import cn.redture.campus.rag.repository.KnowledgeRepository;
import cn.redture.campus.rag.retrieval.CurriculumMetadata;
import cn.redture.campus.util.SecurityContextHolderUtil;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentIngestionService {

    private final RagProperties properties;
    private final DocumentTextExtractor textExtractor;
    private final ChunkerRouter chunkerRouter;
    private final QwenEmbeddingService embeddingService;
    private final KnowledgeRepository repository;

    public DocumentIngestionService(
        RagProperties properties,
        DocumentTextExtractor textExtractor,
        ChunkerRouter chunkerRouter,
        QwenEmbeddingService embeddingService,
        KnowledgeRepository repository) {
        this.properties = properties;
        this.textExtractor = textExtractor;
        this.chunkerRouter = chunkerRouter;
        this.embeddingService = embeddingService;
        this.repository = repository;
    }

    /**
     * 使用默认的空进度回调完成同步入库，保存原文、父子切片和子向量后立即激活文档。
     *
     * @param file    原始上传文件或异步任务提供的文件视图
     * @param request 标题、版本、策略及范围参数，缺失文档元数据可由解析阶段补齐
     * @return 已激活文档的标识、采用策略及父子块数量
     */
    public DocumentUploadResponseVO ingest(MultipartFile file, DocumentUploadRequestDTO request) {
        return ingest(file, request, (phase, percent) -> {
        });
    }

    /**
     * 通过指定回调报告同步入库进度，完成正文复核、切片和子向量持久化后立即激活文档。
     *
     * @param file     原始上传文件或异步任务提供的文件视图
     * @param request  标题、版本、策略及范围参数，缺失文档元数据可由解析阶段补齐
     * @param progress 接收入库阶段和百分比的回调，回调可终止本次执行
     * @return 已激活文档的标识、采用策略及父子块数量
     */
    public DocumentUploadResponseVO ingest(
        MultipartFile file,
        DocumentUploadRequestDTO request,
        java.util.function.BiConsumer<String, Integer> progress) {
        return ingest(file, request, progress, true);
    }

    /**
     * 保存原文、提取正文并复核解析结果，按策略切片后仅对子块生成向量并维护父子关联；可由异步调用方延后发布。
     *
     * @param file     原始上传文件或异步任务提供的文件视图
     * @param request  标题、版本、策略及范围参数，缺失文档元数据可由解析阶段补齐
     * @param progress 接收入库阶段和百分比的回调，回调可终止本次执行
     * @param publish  是否在准备完成后立即激活文档；为 {@code false} 时交由任务发布事务处理
     * @return 文档标识、采用策略、父子块数量及准备或发布状态
     */
    public DocumentUploadResponseVO ingest(
        MultipartFile file,
        DocumentUploadRequestDTO request,
        java.util.function.BiConsumer<String, Integer> progress,
        boolean publish) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Upload file cannot be empty");
        }
        if (request == null) {
            request = new DocumentUploadRequestDTO();
        }
        Long userId = SecurityContextHolderUtil.getUserId();
        String originalFilename =
            file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String fileType = resolveFileType(originalFilename);
        String title =
            StringUtils.hasText(request.getTitle())
                ? request.getTitle()
                : stripExtension(originalFilename);
        DocumentChunker chunker = chunkerRouter.resolve(request.getStrategy(), fileType);

        progress.accept("storing", 5);
        Path storedPath = storeFile(file, originalFilename);
        progress.accept("extracting", 15);
        var extraction =
            textExtractor.extractDetailed(
                storedPath, originalFilename, file.getContentType(), fileType);
        String text = extraction.text();
        if (Boolean.TRUE.equals(extraction.metadata().get("review_required"))) {
            throw new IllegalArgumentException(
                "PDF extraction requires manual review before embedding: "
                    + extraction.metadata().get("issues"));
        }
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException(noExtractableTextMessage(originalFilename, file));
        }
        if (!StringUtils.hasText(request.getDocumentType())) {
            request.setDocumentType(text.contains("[TABLE_ROW ") ? "curriculum" : "general");
        }
        if ("curriculum".equals(request.getDocumentType())) {
            var metadata = CurriculumMetadata.extract(text);
            if (request.getTargetGrade() == null) {
                request.setTargetGrade(metadata.enrollmentYear());
            }
            if (!StringUtils.hasText(request.getTargetMajor())) {
                request.setTargetMajor(metadata.major());
            }
        }
        progress.accept("chunking", 30);
        List<ChunkUnit> chunks =
            chunker.split(text).stream()
                .filter(chunk -> StringUtils.hasText(chunk.getContent()))
                .toList();
        // 嵌入输入与后续子块写入保持相同顺序；父正文仅用于补全上下文，不占用向量位置。
        List<String> childTexts =
            chunks.stream()
                .filter(chunk -> "child".equals(chunk.getLevel()))
                .map(ChunkUnit::getContent)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (childTexts.isEmpty()) {
            throw new IllegalArgumentException("Document did not produce valid child chunks");
        }
        progress.accept("embedding", 40);
        List<float[]> childEmbeddings = embeddingService.embedDocuments(childTexts);
        if (childEmbeddings.size() != childTexts.size()) {
            throw new IllegalStateException(
                "Embedding result count does not match child chunk count");
        }

        Map<String, Object> docMetadata = docMetadata(request, originalFilename);
        docMetadata.put("extraction", extraction.metadata());
        docMetadata.put(
            "embedding",
            Map.of(
                "model",
                properties.getQwen().getEmbeddingModel(),
                "dimensions",
                properties.getQwen().getEmbeddingDimension(),
                "batch_id",
                UUID.randomUUID().toString()));
        Map<String, Object> strategyMetadata =
            Map.of(
                "type", chunker.strategy(),
                "revision", "parent-context-v3",
                "params",
                Map.of(
                    "chunk_size", properties.getChunking().getChunkSize(),
                    "overlap", properties.getChunking().getOverlap(),
                    "parent_size", properties.getChunking().getParentSize(),
                    "child_size", properties.getChunking().getChildSize()));
        String version = StringUtils.hasText(request.getVersion()) ? request.getVersion() : "v1";
        progress.accept("persisting", 75);
        Long docId =
            repository.insertDocument(
                title,
                storedPath.toString(),
                fileType,
                version,
                docMetadata,
                strategyMetadata,
                userId);

        try {
            // 切片策略给出的是父组索引，持久化后需映射为数据库父块标识。
            Map<Integer, Long> parentIds = new HashMap<>();
            int parentCount = 0;
            int childCount = 0;
            int parentIndex = -1;
            int embeddingIndex = 0;

            for (ChunkUnit chunk : chunks) {
                Map<String, Object> metadata = chunkMetadata(docMetadata, chunk);
                if ("parent".equals(chunk.getLevel())) {
                    parentIndex++;
                    Long parentId =
                        repository.insertChunk(
                            docId,
                            null,
                            "parent",
                            chunk.getContent(),
                            null,
                            metadata,
                            request.getTargetMajor(),
                            request.getTargetGrade());
                    parentIds.put(parentIndex, parentId);
                    parentCount++;
                } else {
                    metadata.put("embedding_text_sha256", textHash(chunk.getContent()));
                    Long parentId =
                        chunk.getParentIndex() == null
                            ? null
                            : parentIds.get(chunk.getParentIndex());
                    if ("parent_child".equals(chunker.strategy()) && parentId == null) {
                        throw new IllegalStateException(
                            "Parent-child chunk has no persisted parent");
                    }
                    // 写入子块时推进向量游标，保持向量与子块正文顺序一致。
                    float[] embedding = childEmbeddings.get(embeddingIndex++);
                    repository.insertChunk(
                        docId,
                        parentId,
                        "child",
                        chunk.getContent(),
                        embedding,
                        metadata,
                        request.getTargetMajor(),
                        request.getTargetGrade());
                    childCount++;
                    if (childCount % 20 == 0) {
                        progress.accept(
                            "persisting", 75 + (int) (20.0 * childCount / childTexts.size()));
                    }
                }
            }
            progress.accept("publishing", 97);
            // 异步任务在同一事务内发布新版本并提交任务终态。
            if (publish) {
                repository.updateDocumentStatus(docId, "active");
            }
            return DocumentUploadResponseVO.builder()
                .docId(docId)
                .title(title)
                .fileType(fileType)
                .strategy(chunker.strategy())
                .parentChunks(parentCount)
                .childChunks(childCount)
                .status(publish ? "active" : "processing")
                .build();
        } catch (RuntimeException e) {
            repository.updateDocumentStatus(docId, "failed");
            throw e;
        }
    }

    private String textHash(String text) {
        try {
            return java.util.HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(
                            text.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }

    /**
     * 在配置存储目录内生成唯一文件名并保存原文，校验规范化目标路径没有逃逸到目录外。
     *
     * @param file             需要复制内容的上传文件
     * @param originalFilename 原始文件名，用于生成经清理的保存名称
     * @return 保存后的规范化原文路径
     */
    private Path storeFile(MultipartFile file, String originalFilename) {
        try {
            Path baseDir =
                Path.of(properties.getStorage().getLocalFileDir()).toAbsolutePath().normalize();
            Files.createDirectories(baseDir);
            String storedName =
                UUID.randomUUID() + "-" + originalFilename.replaceAll("[\\\\/:*?\"<>|]", "_");
            Path target = baseDir.resolve(storedName).normalize();
            if (!target.startsWith(baseDir)) {
                throw new IllegalArgumentException("Invalid file path");
            }
            try (var input = file.getInputStream()) {
                Files.copy(input, target);
            }
            return target;
        } catch (Exception e) {
            throw new IllegalArgumentException("Store file failed: " + e.getMessage(), e);
        }
    }

    private String noExtractableTextMessage(String originalFilename, MultipartFile file) {
        return "Document has no extractable text. file="
            + originalFilename
            + ", size="
            + file.getSize()
            + ", contentType="
            + file.getContentType()
            + ". Please upload a text-based pdf/docx/xlsx/md/html/txt file. If this is a"
            + " scanned PDF or image-only document, OCR is required before ingestion.";
    }

    private Map<String, Object> docMetadata(
        DocumentUploadRequestDTO request, String originalFilename) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("file_name", originalFilename);
        metadata.put("document_type", request.getDocumentType());
        metadata.put("department", request.getDepartment());
        metadata.put("effective_date", request.getEffectiveDate());
        metadata.put("target_major", request.getTargetMajor());
        metadata.put("target_grade", request.getTargetGrade());
        metadata.put("target_cohort", request.getTargetCohort());
        return metadata;
    }

    /**
     * 复制文档元数据并追加切片级别、字符估算量以及可用的课程表页行校验信息。
     *
     * @param docMetadata 继承到当前切片的文档范围和来源元数据
     * @param chunk       提供级别、正文及类型的当前切片
     * @return 从文档元数据复制并补全的切片元数据
     */
    private Map<String, Object> chunkMetadata(Map<String, Object> docMetadata, ChunkUnit chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>(docMetadata);
        metadata.put("chunk_type", chunk.getChunkType());
        metadata.put("token_count", chunk.getTokenCount());
        metadata.put("chunk_level", chunk.getLevel());
        metadata.putAll(TableRowBlocks.metadata(chunk.getContent()));
        return metadata;
    }

    private String resolveFileType(String filename) {
        String lower = filename.toLowerCase();
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("File type is required");
        }
        String ext = lower.substring(dot + 1);
        if (!List.of("pdf", "docx", "xlsx", "md", "html", "txt").contains(ext)) {
            throw new IllegalArgumentException("Unsupported file type: " + ext);
        }
        return ext;
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
