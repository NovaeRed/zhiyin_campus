package cn.redture.campus.rag.retrieval;

/**
 * 通用证据门槛只检查召回信号；业务版本由专用执行器和结构化文档范围负责。
 */
public final class EvidencePolicy {
    private EvidencePolicy() {
    }

    public static boolean supports(RetrievedChunk chunk, double minimumCoverage) {
        return chunk.getEvidenceScore() != null
            && Double.isFinite(chunk.getEvidenceScore())
            && chunk.getEvidenceScore() >= minimumCoverage;
    }
}
