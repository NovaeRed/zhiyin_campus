package cn.redture.campus.rag.repository;

import cn.redture.campus.mapper.KnowledgeMapper;
import cn.redture.campus.rag.retrieval.RetrievedChunk;
import cn.redture.campus.rag.retrieval.QueryTextNormalizer;
import cn.redture.campus.rag.retrieval.AcademicScope;
import cn.redture.campus.rag.retrieval.CurriculumDocument;
import cn.redture.campus.rag.retrieval.CurriculumRoute;
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
            if (knowledgeMapper.touchOwnedSession(sessionId, userId) != 1) {
                throw new cn.redture.campus.exception.BaseException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "会话不存在或无权访问", "SESSION_NOT_FOUND");
            }
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
    public void queryPhase(Long id,String phase) { knowledgeMapper.queryPhase(id,phase); }
    public void rewriteQuery(Long id,String query) { knowledgeMapper.rewriteQuery(id,query); }
    public void failQuery(Long id,String code) { knowledgeMapper.failQuery(id,code); }

    public List<RetrievedChunk> searchByVector(float[] embedding, String targetMajor, Integer targetGrade,
                                               String targetCohort, int limit) {
        return searchByVector(embedding, targetMajor, targetGrade, targetCohort, limit, null);
    }

    public List<RetrievedChunk> searchByVector(float[] embedding, String targetMajor, Integer targetGrade,
                                               String targetCohort, int limit, List<Long> docIds) {
        return knowledgeMapper.searchByVector(toVector(embedding), targetMajor, targetGrade, targetCohort, limit, docIds,
                CurriculumRoute.excludedMajors(targetMajor, targetMajor == null ? List.of() : activeCurricula()));
    }

    public List<CurriculumDocument> activeCurricula() {
        return knowledgeMapper.activeCurricula();
    }

    public List<RetrievedChunk> curriculumCourseChunks(Long docId) {
        return knowledgeMapper.curriculumCourseChunks(docId);
    }

    public List<RetrievedChunk> searchByKeyword(String query, String targetMajor, Integer targetGrade,
                                                String targetCohort, int limit) {
        return searchByKeyword(query, targetMajor, targetGrade, targetCohort, limit, null);
    }

    public List<RetrievedChunk> searchByKeyword(String query, String targetMajor, Integer targetGrade,
                                                String targetCohort, int limit, List<Long> docIds) {
        AcademicScope scope = AcademicScope.resolve(query, targetGrade, targetCohort);
        var catalog = activeCurricula();
        String major = CurriculumRoute.effectiveMajor(query, targetMajor, catalog);
        var route = CurriculumRoute.resolve(query, scope, catalog);
        if (route.unavailable()) return List.of();
        // Explicit query scope can narrow a caller-supplied allow-list, never widen it.
        List<Long> allowed = route.docIds() == null ? docIds : docIds == null ? route.docIds()
                : docIds.stream().filter(route.docIds()::contains).toList();
        return knowledgeMapper.searchByKeyword(query, QueryTextNormalizer.keywords(query), major, scope.grade(),
                scope.cohort(), limit, allowed, CurriculumRoute.excludedMajors(major, catalog));
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
