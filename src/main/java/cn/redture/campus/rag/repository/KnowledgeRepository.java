package cn.redture.campus.rag.repository;

import cn.redture.campus.mapper.KnowledgeMapper;
import cn.redture.campus.rag.retrieval.CurriculumDocument;
import cn.redture.campus.rag.retrieval.QueryTextNormalizer;
import cn.redture.campus.rag.retrieval.RetrievedChunk;

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

    public Long insertDocument(
        String title,
        String filePath,
        String fileType,
        String version,
        Map<String, Object> metadata,
        Map<String, Object> chunkStrategy,
        Long createdBy) {
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

    /**
     * 写入父块或子块；父块可不带向量，子块的父标识由调用方按已持久化父索引映射提供。
     *
     * @param docId       所属文档标识
     * @param parentId    已持久化父块标识，递归子块或父块本身可为空
     * @param level       切片层级parent或child
     * @param content     保留结构的正文
     * @param embedding   子块向量，父块传入 {@code null}
     * @param metadata    切片来源、范围与校验信息
     * @param targetMajor 可选适用专业
     * @param targetGrade 可选入学年份
     * @return 新切片的数据库标识
     */
    public Long insertChunk(
        Long docId,
        Long parentId,
        String level,
        String content,
        float[] embedding,
        Map<String, Object> metadata,
        String targetMajor,
        Integer targetGrade) {
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

    /**
     * 创建新会话，或在校验现有会话归属及未删除状态后更新其访问时间。
     *
     * @param userId    当前用户标识
     * @param sessionId 需要继续的会话标识，为 {@code null} 时创建新会话
     * @return 可供当前用户执行问答的会话标识
     */
    public Long ensureSession(Long userId, Long sessionId) {
        if (sessionId != null) {
            if (knowledgeMapper.touchOwnedSession(sessionId, userId) != 1) {
                throw new cn.redture.campus.exception.BaseException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "会话不存在或无权访问",
                    "SESSION_NOT_FOUND");
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

    public void queryPhase(Long id, String phase) {
        knowledgeMapper.queryPhase(id, phase);
    }

    public void rewriteQuery(Long id, String query) {
        knowledgeMapper.rewriteQuery(id, query);
    }

    public void failQuery(Long id, String code) {
        knowledgeMapper.failQuery(id, code);
    }

    public void recordIntent(Long id, String domain, String task, boolean fallback) {
        String category = (fallback ? "fallback:" : "") + domain + ":" + task;
        knowledgeMapper.recordIntent(id, category.substring(0, Math.min(50, category.length())));
    }

    /**
     * 按结构化范围执行向量召回，并由SQL关联命中子块的父正文。
     *
     * @param embedding    维度已验证的查询向量
     * @param targetMajor  可选专业条件，为 {@code null} 时不按该列限制
     * @param targetGrade  可选入学年份条件
     * @param targetCohort 可选毕业届别条件
     * @param limit        当前召回路的候选数量上限
     * @return 保留命中子块标识及父上下文的有序候选
     */
    public List<RetrievedChunk> searchByVector(
        float[] embedding,
        String targetMajor,
        Integer targetGrade,
        String targetCohort,
        int limit) {
        return searchByVector(embedding, targetMajor, targetGrade, targetCohort, limit, null);
    }

    /**
     * 按结构化范围执行向量召回，并由SQL关联命中子块的父正文。
     *
     * @param embedding    维度已验证的查询向量
     * @param targetMajor  可选专业条件，为 {@code null} 时不按该列限制
     * @param targetGrade  可选入学年份条件
     * @param targetCohort 可选毕业届别条件
     * @param limit        当前召回路的候选数量上限
     * @param docIds       文档白名单；{@code null} 表示不限制，空列表表示没有可检索文档
     * @return 保留命中子块标识及父上下文的有序候选
     */
    public List<RetrievedChunk> searchByVector(
        float[] embedding,
        String targetMajor,
        Integer targetGrade,
        String targetCohort,
        int limit,
        List<Long> docIds) {
        return knowledgeMapper.searchByVector(
            toVector(embedding),
            targetMajor,
            targetGrade,
            targetCohort,
            limit,
            docIds,
            List.of());
    }

    /**
     * 根据有效状态、课程记录和类型标记读取培养方案目录。
     *
     * @return 聚合了文档标识、标题、专业及入学年份的目录
     */
    public List<CurriculumDocument> activeCurricula() {
        return knowledgeMapper.activeCurricula();
    }

    /**
     * 读取指定有效文档的全部课程表子块，并关联父上下文。
     *
     * @param docId 已由专用执行器确定的培养方案文档标识
     * @return 按切片标识排列的完整课程记录来源
     */
    public List<RetrievedChunk> curriculumCourseChunks(Long docId) {
        return knowledgeMapper.curriculumCourseChunks(docId);
    }

    /**
     * 按结构化范围执行关键词召回，并由SQL关联命中子块的父正文。
     *
     * @param query        用于词面召回的检索表达
     * @param targetMajor  可选专业条件，为 {@code null} 时不按该列限制
     * @param targetGrade  可选入学年份条件
     * @param targetCohort 可选毕业届别条件
     * @param limit        当前召回路的候选数量上限
     * @return 保留命中子块标识及父上下文的有序候选
     */
    public List<RetrievedChunk> searchByKeyword(
        String query, String targetMajor, Integer targetGrade, String targetCohort, int limit) {
        return searchByKeyword(query, targetMajor, targetGrade, targetCohort, limit, null);
    }

    /**
     * 按结构化范围执行关键词召回，并由SQL关联命中子块的父正文。
     *
     * @param query        用于词面召回的检索表达
     * @param targetMajor  可选专业条件，为 {@code null} 时不按该列限制
     * @param targetGrade  可选入学年份条件
     * @param targetCohort 可选毕业届别条件
     * @param limit        当前召回路的候选数量上限
     * @param docIds       文档白名单；{@code null} 表示不限制，空列表表示没有可检索文档
     * @return 保留命中子块标识及父上下文的有序候选
     */
    public List<RetrievedChunk> searchByKeyword(
        String query,
        String targetMajor,
        Integer targetGrade,
        String targetCohort,
        int limit,
        List<Long> docIds) {
        return knowledgeMapper.searchByKeyword(
            query,
            QueryTextNormalizer.keywords(query),
            targetMajor,
            targetGrade,
            targetCohort,
            limit,
            docIds,
            List.of());
    }

    /**
     * 保存融合候选、最终选中来源与本次筛选参数，供后续只读评测和问题追踪。
     *
     * @param queryId      当前查询标识
     * @param initial      已融合的初始候选列表
     * @param reranked     通过筛选并最终采用的来源列表
     * @param topK         本次请求的最终候选上限
     * @param bm25Weight   检索轨迹中的关键词权重
     * @param vectorWeight 追踪记录中的向量权重字段
     * @param threshold    本次使用的重排阈值
     */
    public void insertRetrievalTrace(
        Long queryId,
        List<RetrievedChunk> initial,
        List<RetrievedChunk> reranked,
        int topK,
        double bm25Weight,
        double vectorWeight,
        double threshold) {
        String selectedChunkIdsCsv =
            reranked.stream()
                .map(RetrievedChunk::getChunkId)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        knowledgeMapper.upsertRetrievalTrace(
            queryId,
            toJson(initial),
            toJson(reranked),
            selectedChunkIdsCsv,
            topK,
            bm25Weight,
            vectorWeight,
            threshold);
    }

    /**
     * 按查询保存实际执行模型、模板和文本，包括确定性结果或未发布的生成记录。
     *
     * @param queryId        当前查询标识
     * @param model          实际模型名称或结构化执行分支标识
     * @param promptTemplate 生成或直接执行模板版本
     * @param answer         需要保留的最终文本
     */
    public void insertGenerationTrace(
        Long queryId, String model, String promptTemplate, String answer) {
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
