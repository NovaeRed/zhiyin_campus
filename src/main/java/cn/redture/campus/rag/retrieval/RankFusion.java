package cn.redture.campus.rag.retrieval;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每个查询及召回通道独立排名，同一列表中的重复 ID 只计一次。
 */
public final class RankFusion {
    private RankFusion() {
    }

    public static List<RetrievedChunk> fuse(
        List<List<RetrievedChunk>> vectorRoutes,
        List<List<RetrievedChunk>> keywordRoutes,
        int k) {
        if (k < 1) {
            throw new IllegalArgumentException("rrfK must be positive");
        }
        Map<Long, RetrievedChunk> merged = new LinkedHashMap<>();
        for (var route : vectorRoutes) {
            add(merged, route, k, true);
        }
        for (var route : keywordRoutes) {
            add(merged, route, k, false);
        }
        int routeCount = vectorRoutes.size() + keywordRoutes.size();
        for (var chunk : merged.values()) {
            chunk.setNormalizedFusedScore(chunk.getFusedScore() * (k + 1) / routeCount);
        }
        return merged.values().stream()
            .sorted(
                Comparator.comparing(RetrievedChunk::getFusedScore)
                    .reversed()
                    .thenComparing(RetrievedChunk::getChunkId))
            .toList();
    }

    private static void add(
        Map<Long, RetrievedChunk> merged, List<RetrievedChunk> route, int k, boolean vector) {
        Set<Long> seen = new HashSet<>();
        int rank = 0;
        for (RetrievedChunk hit : route) {
            if (!seen.add(hit.getChunkId())) {
                continue;
            }
            RetrievedChunk chunk =
                merged.computeIfAbsent(
                    hit.getChunkId(),
                    id ->
                        RetrievedChunk.builder()
                            .chunkId(id)
                            .docId(hit.getDocId())
                            .parentId(hit.getParentId())
                            .title(hit.getTitle())
                            .content(hit.getContent())
                            .matchedContent(hit.getMatchedContent())
                            .targetGrade(hit.getTargetGrade())
                            .metadata(hit.getMetadata())
                            .fusedScore(0.0)
                            .build());
            chunk.setFusedScore(chunk.getFusedScore() + 1.0 / (k + ++rank));
            if (vector) {
                chunk.setVectorScore(max(chunk.getVectorScore(), hit.getVectorScore()));
            } else {
                chunk.setBm25Score(max(chunk.getBm25Score(), hit.getBm25Score()));
            }
        }
    }

    private static Double max(Double a, Double b) {
        if (b == null || !Double.isFinite(b)) {
            return a;
        }
        return a == null ? b : Math.max(a, b);
    }
}
