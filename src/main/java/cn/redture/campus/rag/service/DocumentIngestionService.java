package cn.redture.campus.rag.service;

import cn.redture.campus.config.RagProperties;
import cn.redture.campus.pojo.dto.DocumentUploadRequestDTO;
import cn.redture.campus.pojo.vo.DocumentUploadResponseVO;
import cn.redture.campus.rag.chunk.ChunkUnit;
import cn.redture.campus.rag.chunk.ChunkerRouter;
import cn.redture.campus.rag.chunk.DocumentChunker;
import cn.redture.campus.rag.chunk.TableRowBlocks;
import cn.redture.campus.rag.repository.KnowledgeRepository;
import cn.redture.campus.rag.retrieval.AcademicScope;
import cn.redture.campus.rag.retrieval.CurriculumRoute;
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

    public DocumentIngestionService(RagProperties properties,
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

    public DocumentUploadResponseVO ingest(MultipartFile file, DocumentUploadRequestDTO request) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Upload file cannot be empty");
        }
        if (request == null) {
            request = new DocumentUploadRequestDTO();
        }
        Long userId = SecurityContextHolderUtil.getUserId();
        String originalFilename = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String fileType = resolveFileType(originalFilename);
        String title = StringUtils.hasText(request.getTitle()) ? request.getTitle() : stripExtension(originalFilename);
        DocumentChunker chunker = chunkerRouter.resolve(request.getStrategy(), fileType);

        Path storedPath = storeFile(file, originalFilename);
        var extraction = textExtractor.extractDetailed(storedPath, originalFilename, file.getContentType(), fileType);
        String text = extraction.text();
        if (Boolean.TRUE.equals(extraction.metadata().get("review_required"))) {
            throw new IllegalArgumentException("PDF extraction requires manual review before embedding: "
                    + extraction.metadata().get("issues"));
        }
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException(noExtractableTextMessage(originalFilename, file));
        }
        if (request.getTargetGrade() == null) request.setTargetGrade(AcademicScope.inferEnrollmentYear(text));
        if (!StringUtils.hasText(request.getTargetMajor())) request.setTargetMajor(CurriculumRoute.inferMajor(text));
        List<ChunkUnit> chunks = chunker.split(text).stream()
                .filter(chunk -> StringUtils.hasText(chunk.getContent()))
                .toList();
        List<String> childTexts = chunks.stream()
                .filter(chunk -> "child".equals(chunk.getLevel()))
                .map(ChunkUnit::getContent)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (childTexts.isEmpty()) {
            throw new IllegalArgumentException("Document did not produce valid child chunks");
        }
        List<float[]> childEmbeddings = embeddingService.embedDocuments(childTexts);
        if (childEmbeddings.size() != childTexts.size()) {
            throw new IllegalStateException("Embedding result count does not match child chunk count");
        }

        Map<String, Object> docMetadata = docMetadata(request, originalFilename);
        docMetadata.put("extraction", extraction.metadata());
        docMetadata.put("embedding", Map.of("model", properties.getQwen().getEmbeddingModel(),
                "dimensions", properties.getQwen().getEmbeddingDimension(), "batch_id", UUID.randomUUID().toString()));
        Map<String, Object> strategyMetadata = Map.of(
                "type", chunker.strategy(),
                "revision", "parent-context-v3",
                "params", Map.of(
                        "chunk_size", properties.getChunking().getChunkSize(),
                        "overlap", properties.getChunking().getOverlap(),
                        "parent_size", properties.getChunking().getParentSize(),
                        "child_size", properties.getChunking().getChildSize()
                )
        );
        String version = StringUtils.hasText(request.getVersion()) ? request.getVersion() : "v1";
        Long docId = repository.insertDocument(title, storedPath.toString(), fileType, version, docMetadata, strategyMetadata, userId);

        try {
            Map<Integer, Long> parentIds = new HashMap<>();
            int parentCount = 0;
            int childCount = 0;
            int parentIndex = -1;
            int embeddingIndex = 0;

            for (ChunkUnit chunk : chunks) {
                Map<String, Object> metadata = chunkMetadata(docMetadata, chunk);
                if ("parent".equals(chunk.getLevel())) {
                    parentIndex++;
                    Long parentId = repository.insertChunk(docId, null, "parent", chunk.getContent(), null, metadata,
                            request.getTargetMajor(), request.getTargetGrade());
                    parentIds.put(parentIndex, parentId);
                    parentCount++;
                } else {
                    metadata.put("embedding_text_sha256", textHash(chunk.getContent()));
                    Long parentId = chunk.getParentIndex() == null ? null : parentIds.get(chunk.getParentIndex());
                    if ("parent_child".equals(chunker.strategy()) && parentId == null) {
                        throw new IllegalStateException("Parent-child chunk has no persisted parent");
                    }
                    float[] embedding = childEmbeddings.get(embeddingIndex++);
                    repository.insertChunk(docId, parentId, "child", chunk.getContent(), embedding, metadata,
                            request.getTargetMajor(), request.getTargetGrade());
                    childCount++;
                }
            }
            repository.updateDocumentStatus(docId, "active");
            return DocumentUploadResponseVO.builder()
                    .docId(docId)
                    .title(title)
                    .fileType(fileType)
                    .strategy(chunker.strategy())
                    .parentChunks(parentCount)
                    .childChunks(childCount)
                    .status("active")
                    .build();
        } catch (RuntimeException e) {
            repository.updateDocumentStatus(docId, "failed");
            throw e;
        }
    }

    private String textHash(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required", unavailable);
        }
    }

    private Path storeFile(MultipartFile file, String originalFilename) {
        try {
            Path baseDir = Path.of(properties.getStorage().getLocalFileDir()).toAbsolutePath().normalize();
            Files.createDirectories(baseDir);
            String storedName = UUID.randomUUID() + "-" + originalFilename.replaceAll("[\\\\/:*?\"<>|]", "_");
            Path target = baseDir.resolve(storedName).normalize();
            if (!target.startsWith(baseDir)) {
                throw new IllegalArgumentException("Invalid file path");
            }
            Files.copy(file.getInputStream(), target);
            return target;
        } catch (Exception e) {
            throw new IllegalArgumentException("Store file failed: " + e.getMessage(), e);
        }
    }

    private String noExtractableTextMessage(String originalFilename, MultipartFile file) {
        return "Document has no extractable text. file=" + originalFilename
                + ", size=" + file.getSize()
                + ", contentType=" + file.getContentType()
                + ". Please upload a text-based pdf/docx/xlsx/md/html/txt file. "
                + "If this is a scanned PDF or image-only document, OCR is required before ingestion.";
    }

    private Map<String, Object> docMetadata(DocumentUploadRequestDTO request, String originalFilename) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("file_name", originalFilename);
        metadata.put("department", request.getDepartment());
        metadata.put("effective_date", request.getEffectiveDate());
        metadata.put("target_major", request.getTargetMajor());
        metadata.put("target_grade", request.getTargetGrade());
        metadata.put("target_cohort", request.getTargetCohort());
        return metadata;
    }

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
