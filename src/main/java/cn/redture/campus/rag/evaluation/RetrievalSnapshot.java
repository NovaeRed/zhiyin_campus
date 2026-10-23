package cn.redture.campus.rag.evaluation;

import cn.redture.campus.rag.retrieval.RetrievedChunk;

import java.util.List;

/** The exact recorded context, independent of later document edits or model calls. */
public record RetrievalSnapshot(Long queryId, String traceId, String question, String rewrittenQuery,
                                int recordedTopK, double threshold,
                                List<RetrievedChunk> fused, List<RetrievedChunk> selected) { }
