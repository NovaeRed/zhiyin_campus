package cn.redture.campus.rag.retrieval;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class SimpleContentReranker implements Reranker {

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK) {
        Set<String> queryTerms = QueryTextNormalizer.terms(query);
        return chunks.stream()
                .peek(chunk -> chunk.setRerankScore(score(queryTerms, chunk)))
                .sorted(Comparator.comparing(RetrievedChunk::getRerankScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(topK)
                .toList();
    }

    private double score(Set<String> queryTerms, RetrievedChunk chunk) {
        String content = chunk.getContent() == null ? "" : chunk.getContent().toLowerCase();
        long hits = queryTerms.stream().filter(content::contains).count();
        double lexical = queryTerms.isEmpty() ? 0 : (double) hits / Math.min(queryTerms.size(), 8);
        lexical = Math.min(1.0, lexical);
        double vector = normalized(chunk.getVectorScore());
        double keyword = chunk.getBm25Score() == null ? 0 : Math.min(1.0, Math.max(0, chunk.getBm25Score()) / 3.0);
        double fused = chunk.getFusedScore() == null ? 0 : chunk.getFusedScore();
        return fused + (0.25 * vector) + (0.65 * lexical) + (0.10 * keyword);
    }

    private double normalized(Double score) {
        if (score == null || score.isNaN()) {
            return 0;
        }
        return Math.min(1.0, Math.max(0, score));
    }
}
