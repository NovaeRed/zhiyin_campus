package cn.redture.campus.rag.retrieval;

import java.util.List;

public interface Reranker {
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK);
}
