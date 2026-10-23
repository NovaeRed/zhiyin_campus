package cn.redture.campus.rag.evaluation;

import java.util.Locale;

public final class EvaluationMarkdown {
    private EvaluationMarkdown() { }

    public static String render(EvaluationReport report) {
        StringBuilder out = new StringBuilder("# RAG 检索效果评测\n\n");
        out.append("运行：").append(report.runId()).append("\n\n数据集：").append(safe(report.datasetVersion()))
                .append("\n\n时间：").append(report.createdAt()).append("\n\n规则：").append(report.rubricVersion())
                .append("\n\n| 阶段 | K | 样本数 | 平均分 | 通过率 | Precision | Recall | MRR | nDCG | HitRate |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (var s : report.summaries()) {
            out.append("| ").append(s.stage()).append(" | ").append(s.k()).append(" | ").append(s.sampleCount())
                    .append(" | ").append(number(s.meanScore())).append(" | ").append(number(s.passRate()))
                    .append(" | ").append(number(s.meanPrecision())).append(" | ").append(number(s.meanRecall()))
                    .append(" | ").append(number(s.mrr())).append(" | ").append(number(s.meanNdcg()))
                    .append(" | ").append(number(s.hitRate())).append(" |\n");
        }
        for (var c : report.cases()) {
            out.append("\n## ").append(safe(c.sampleId())).append(" / ").append(c.stage()).append("@").append(c.k())
                    .append("\n\n问题：").append(safe(c.question())).append("\n\n改写：").append(safe(c.rewrittenQuery()))
                    .append("\n\nqueryId=").append(c.queryId()).append("，traceId=").append(safe(c.traceId()))
                    .append("，原始finalTopK=").append(c.recordedTopK()).append("，原始阈值=").append(c.recordedThreshold())
                    .append("\n\n得分：").append(number(c.metrics().score())).append("；通过：").append(c.metrics().passed())
                    .append("；缺失证据：").append(safe(c.missingEvidence().toString())).append("\n");
            for (String warning : c.warnings()) out.append("\n提示：").append(safe(warning)).append("\n");
            for (var m : c.matches()) {
                var chunk = m.chunk();
                out.append("\n### 第 ").append(m.rank()).append(" 名：").append(safe(chunk.getTitle()))
                        .append("\n\ndocId=").append(chunk.getDocId()).append("，chunkId=").append(chunk.getChunkId())
                        .append("，parentId=").append(chunk.getParentId())
                        .append("\n\n向量分：").append(number(chunk.getVectorScore())).append("；关键词分：").append(number(chunk.getBm25Score()))
                        .append("；融合分：").append(number(chunk.getFusedScore())).append("；重排分：").append(number(chunk.getRerankScore()))
                        .append("\n\n相关等级：").append(m.relevance()).append("；理由：").append(safe(m.reason()))
                        .append("\n\n正文：\n\n").append(safe(chunk.getContent())).append("\n");
            }
        }
        return out.toString();
    }

    private static String number(Double value) { return value == null ? "N/A" : String.format(Locale.ROOT, "%.4f", value); }
    // Documents are untrusted; render as text, never raw HTML or active Markdown links.
    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("`", "\\`").replace("[", "\\[").replace("]", "\\]")
                .replace("*", "\\*").replace("_", "\\_").replace("#", "\\#").replace("|", "\\|");
    }
}
