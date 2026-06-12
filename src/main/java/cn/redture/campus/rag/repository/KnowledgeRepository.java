package cn.redture.campus.rag.repository;

import cn.redture.campus.mapper.KnowledgeMapper;
import cn.redture.campus.rag.retrieval.RetrievedChunk;
import cn.redture.campus.rag.retrieval.QueryTextNormalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class KnowledgeRepository {

    private final KnowledgeMapper knowledgeMapper;
    private final ObjectMapper objectMapper;

    public KnowledgeRepository(KnowledgeMapper knowledgeMapper, ObjectMapper objectMapper) {
        this.knowledgeMapper = knowledgeMapper;
        this.objectMapper = objectMapper;
    }

    public Long insertDocument(String title, String filePath, String fileType, String version,
                               Map<String, Object> metadata, Map<String, Object> chunkStrategy, Long createdBy) {
        KnowledgeDocumentInsert record = new KnowledgeDocumentInsert();
        record.setTitle(title);
        record.setFilePath(filePath);
        record.setFileType(fileType);
        record.setVersion(version);
        record.setMetadataJson(toJson(metadata));
        record.setChunkStrategyJson(toJson(chunkStrategy));
        record.setCreatedBy(createdBy);
        knowledgeMapper.insertDocument(record);
        return record.getId();
    }

    public Long insertChunk(Long docId, Long parentId, String level, String content, float[] embedding,
                            Map<String, Object> metadata, String targetMajor, Integer targetGrade) {
        DocChunkInsert record = new DocChunkInsert();
        record.setDocId(docId);
        record.setParentId(parentId);
        record.setLevel(level);
        record.setContent(content);
        record.setEmbeddingVector(embedding == null ? null : toVector(embedding));
        record.setMetadataJson(toJson(metadata));
        record.setTargetMajor(targetMajor);
        record.setTargetGrade(targetGrade);
        knowledgeMapper.insertChunk(record);
        return record.getId();
    }

    public void updateDocumentStatus(Long docId, String status) {
        knowledgeMapper.updateDocumentStatus(docId, status);
        knowledgeMapper.updateChunksStatus(docId, status);
    }

    public Long ensureSession(Long userId, Long sessionId) {
        if (sessionId != null) {
            knowledgeMapper.touchSession(sessionId);
            return sessionId;
        }
        return knowledgeMapper.insertSession(userId);
    }

    public QueryRecord insertQuery(Long sessionId, String originalQuery, String rewrittenQuery) {
        RagQueryInsert record = new RagQueryInsert();
        record.setSessionId(sessionId);
        record.setTraceId(UUID.randomUUID().toString());
        record.setOriginalQuery(originalQuery);
        record.setRewrittenQuery(rewrittenQuery);
        knowledgeMapper.insertQuery(record);
        return new QueryRecord(record.getId(), record.getTraceId());
    }

    public void completeQuery(Long queryId, double confidence) {
        knowledgeMapper.completeQuery(queryId, confidence);
    }

    public List<RetrievedChunk> searchByVector(float[] embedding, String targetMajor, Integer targetGrade, int limit) {
        return knowledgeMapper.searchByVector(toVector(embedding), targetMajor, targetGrade, limit);
    }

    public List<RetrievedChunk> searchByKeyword(String query, String targetMajor, Integer targetGrade, int limit) {
        return knowledgeMapper.searchByKeyword(query, QueryTextNormalizer.keywords(query), targetMajor, targetGrade, limit);
    }

    public void insertRetrievalTrace(Long queryId, List<RetrievedChunk> initial, List<RetrievedChunk> reranked,
                                     int topK, double bm25Weight, double vectorWeight, double threshold) {
        String selectedChunkIdsCsv = reranked.stream()
                .map(RetrievedChunk::getChunkId)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        knowledgeMapper.upsertRetrievalTrace(queryId, toJson(initial), toJson(reranked), selectedChunkIdsCsv,
                topK, bm25Weight, vectorWeight, threshold);
    }

    public void insertGenerationTrace(Long queryId, String model, String promptTemplate, String answer) {
        knowledgeMapper.upsertGenerationTrace(queryId, model, promptTemplate, answer);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize jsonb value", e);
        }
    }

    private String toVector(float[] vector) {
        List<String> values = new ArrayList<>(vector.length);
        for (float v : vector) {
            values.add(Float.toString(v));
        }
        return "[" + String.join(",", values) + "]";
    }

    public record QueryRecord(Long queryId, String traceId) {
    }
}
