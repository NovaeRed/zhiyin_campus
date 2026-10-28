package cn.redture.campus.rag.retrieval;

/**
 * 根据候选证据分和配置门槛筛选回答来源。
 */
public final class EvidencePolicy {
    private EvidencePolicy() {
    }

    /**
     * 检查独立证据分是否存在、为有限数值并达到门槛。
     *
     * @param chunk           已由重排器计算证据分的候选
     * @param minimumCoverage 最低词面证据覆盖要求
     * @return 候选是否通过当前证据门槛
     */
    public static boolean supports(RetrievedChunk chunk, double minimumCoverage) {
        return chunk.getEvidenceScore() != null
            && Double.isFinite(chunk.getEvidenceScore())
            && chunk.getEvidenceScore() >= minimumCoverage;
    }
}
