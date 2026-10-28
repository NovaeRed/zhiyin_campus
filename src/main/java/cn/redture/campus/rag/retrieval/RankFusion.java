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

    /**
     * 以每条查询及召回通道为独立排名列表累计RRF，并保留最高原始分和按路数归一化的融合分。
     *
     * @param vectorRoutes  各个向量查询按相关性排序的候选列表
     * @param keywordRoutes 各个关键词查询按相关性排序的候选列表
     * @param k             正数RRF平滑参数，每路贡献为一除以参数与排名之和
     * @return 按原始融合分降序、同分按子块标识稳定排序的候选
     */
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
            // 原始RRF分用于追踪；另存按召回路数缩放的分数，供后续规则重排使用。
            chunk.setNormalizedFusedScore(chunk.getFusedScore() * (k + 1) / routeCount);
        }
        return merged.values().stream()
            .sorted(
                Comparator.comparing(RetrievedChunk::getFusedScore)
                    .reversed()
                    .thenComparing(RetrievedChunk::getChunkId))
            .toList();
    }

    /**
     * 将一路候选加入融合映射，同一列表中重复子块仅计一次，并维护对应通道的最高原始分。
     *
     * @param merged 按子块标识累计融合结果的可变映射
     * @param route  当前召回路的有序候选
     * @param k      RRF平滑参数
     * @param vector 当前列表是否来自向量通道
     */
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
