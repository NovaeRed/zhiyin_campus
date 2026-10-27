package cn.redture.campus.rag.evaluation;

import cn.redture.campus.rag.retrieval.RetrievedChunk;

import java.util.List;

/**
 * 使用当时记录的原始上下文，不受后续文档编辑或模型调用影响。
 */
public record RetrievalSnapshot(
    Long queryId,
    String traceId,
    String question,
    String rewrittenQuery,
    int recordedTopK,
    double threshold,
    List<RetrievedChunk> fused,
    List<RetrievedChunk> selected) {
}
