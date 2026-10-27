package cn.redture.campus.rag.evaluation;

import cn.redture.campus.rag.retrieval.RetrievedChunk;

import java.util.List;

public record EvaluationReport(
    String runId,
    String createdAt,
    String datasetVersion,
    String rubricVersion,
    EvaluationRequest groundTruth,
    List<CaseResult> cases,
    List<Summary> summaries) {
    public record Match(
        int rank, RetrievedChunk chunk, String evidenceId, int relevance, String reason) {
    }

    public record Metrics(
        double precision,
        Double recall,
        Double reciprocalRank,
        Double ndcg,
        double score,
        boolean passed) {
    }

    public record CaseResult(
        String sampleId,
        Long queryId,
        String traceId,
        String question,
        String rewrittenQuery,
        String stage,
        int k,
        int returnedCount,
        boolean expectEmpty,
        int recordedTopK,
        double recordedThreshold,
        List<String> warnings,
        Metrics metrics,
        List<String> missingEvidence,
        List<Match> matches) {
    }

    public record Summary(
        String stage,
        int k,
        int sampleCount,
        int answerableCount,
        double meanScore,
        double passRate,
        double meanPrecision,
        Double meanRecall,
        Double mrr,
        Double meanNdcg,
        Double hitRate) {
    }
}
