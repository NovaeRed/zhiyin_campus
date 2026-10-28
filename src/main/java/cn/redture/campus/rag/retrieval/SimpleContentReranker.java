package cn.redture.campus.rag.retrieval;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SimpleContentReranker implements Reranker {
    private static final Pattern COURSE_ROW =
        Pattern.compile("(?m)^\\s*\\d{6}\\s+(.+?)\\s+\\d+(?:\\.\\d+)?\\s+20\\d{2}-");

    /**
     * 计算规则重排与独立证据分，按父上下文去重后取前若干项；评分会写回候选对象。
     *
     * @param query  语义计划提供的独立检索问题
     * @param chunks 已融合的候选切片
     * @param topK   父上下文去重后保留的最大数量
     * @return 按重排分降序排列的候选列表
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK) {
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be positive");
        }
        Set<String> queryTerms = QueryTextNormalizer.terms(query);
        List<RetrievedChunk> contexts =
            chunks.stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        this::contextKey,
                        c -> c,
                        (left, right) -> left,
                        LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String term : queryTerms) {
            long frequency =
                contexts.stream().filter(c -> text(c.getContent()).contains(term)).count();
            weights.put(term, Math.log(1 + (double) contexts.size() / (1 + frequency)));
        }
        String canonical = String.join(" ", QueryTextNormalizer.phrases(query));
        Set<String> seen = new HashSet<>();
        return chunks.stream()
            .peek(chunk -> chunk.setRerankScore(score(query, canonical, weights, chunk)))
            .sorted(
                Comparator.comparing(
                    RetrievedChunk::getRerankScore,
                    Comparator.nullsLast(Comparator.reverseOrder())))
            .filter(chunk -> seen.add(contextKey(chunk)))
            .limit(topK)
            .toList();
    }

    private double score(
        String query, String canonical, Map<String, Double> weights, RetrievedChunk chunk) {
        chunk.setEvidenceScore(0.0);
        Map<String, Double> bodyWeights = new LinkedHashMap<>();
        weights.forEach(
            (term, weight) -> {
                if (!text(chunk.getTitle()).contains(term)) {
                    bodyWeights.put(term, weight);
                }
            });
        double lexical =
            coverage(
                bodyWeights.isEmpty() ? weights : bodyWeights,
                canonical,
                chunk.getContent());
        double title = coverage(weights, canonical, chunk.getTitle());
        double vector = normalized(chunk.getVectorScore());
        chunk.setEvidenceScore(0.8 * lexical + 0.2 * title);
        if (!EvidencePolicy.supports(chunk, 0)) {
            chunk.setEvidenceScore(0.0);
            return 0;
        }
        double fused = normalized(chunk.getNormalizedFusedScore());
        return Math.max(
            0,
            Math.min(
                1,
                0.05 * fused
                    + (0.25 * vector)
                    + (0.60 * lexical)
                    + (0.15 * title)
                    - courseVariantPenalty(
                    query, canonical, weights, chunk.getContent())));
    }

    /**
     * 按查询字符位置聚合词面命中的最大权重，计算加权覆盖分。
     *
     * @param weights   根据候选上下文词频构造的查询词权重
     * @param canonical 用于确定字符位置的规范查询表达
     * @param value     待计算覆盖的正文或标题
     * @return 零至一之间的加权词面覆盖分
     */
    private double coverage(Map<String, Double> weights, String canonical, String value) {
        String content = text(value);
        double[] possible = new double[canonical.length()];
        double[] covered = new double[canonical.length()];
        // 每个查询字符仅取匹配词中的最大权重。
        for (var term : weights.entrySet()) {
            boolean hit = content.contains(term.getKey());
            for (int at = canonical.indexOf(term.getKey());
                 at >= 0;
                 at = canonical.indexOf(term.getKey(), at + 1)) {
                for (int i = at; i < at + term.getKey().length(); i++) {
                    possible[i] = Math.max(possible[i], term.getValue());
                    if (hit) {
                        covered[i] = Math.max(covered[i], term.getValue());
                    }
                }
            }
        }
        double total = Arrays.stream(possible).sum();
        return total == 0 ? 0 : Arrays.stream(covered).sum() / total;
    }

    /**
     * 计算学分查询中实验课、课程设计变体的评分惩罚。
     *
     * @param query     当前检索问题
     * @param canonical 规范查询表达
     * @param weights   用于匹配课程名称的查询词权重
     * @param content   候选父上下文或切片正文
     * @return 课程变体惩罚值，未触发规则时为零
     */
    private double courseVariantPenalty(
        String query, String canonical, Map<String, Double> weights, String content) {
        if (!query.contains("学分") || query.contains("实验") || query.contains("课程设计")) {
            return 0;
        }
        var rows = COURSE_ROW.matcher(content == null ? "" : content);
        String bestName = "";
        double best = 0;
        while (rows.find()) {
            String name = rows.group(1);
            double match = coverage(weights, canonical, name);
            if (match > best) {
                best = match;
                bestName = name;
            }
        }
        return bestName.endsWith("实验") || bestName.endsWith("课程设计") ? 0.15 : 0;
    }

    private String text(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private String contextKey(RetrievedChunk chunk) {
        return chunk.getDocId()
            + ":"
            + (chunk.getParentId() == null
            ? "c" + chunk.getChunkId()
            : "p" + chunk.getParentId());
    }

    private double normalized(Double score) {
        if (score == null || !Double.isFinite(score)) {
            return 0;
        }
        return Math.min(1.0, Math.max(0, score));
    }
}
