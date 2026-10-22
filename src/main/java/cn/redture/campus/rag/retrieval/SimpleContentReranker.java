package cn.redture.campus.rag.retrieval;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class SimpleContentReranker implements Reranker {
    private static final Pattern COURSE_ROW = Pattern.compile("(?m)^\\s*\\d{6}\\s+(.+?)\\s+\\d+(?:\\.\\d+)?\\s+20\\d{2}-");

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> chunks, int topK) {
        if (topK < 1) throw new IllegalArgumentException("topK must be positive");
        Set<String> queryTerms = QueryTextNormalizer.terms(query);
        List<RetrievedChunk> contexts = chunks.stream().collect(java.util.stream.Collectors.toMap(
                this::contextKey, c -> c, (left, right) -> left, LinkedHashMap::new)).values().stream().toList();
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String term : queryTerms) {
            long frequency = contexts.stream().filter(c -> text(c.getContent()).contains(term)).count();
            weights.put(term, Math.log(1 + (double) contexts.size() / (1 + frequency)));
        }
        String canonical = String.join(" ", QueryTextNormalizer.phrases(query));
        Set<String> seen = new HashSet<>();
        return chunks.stream()
                .peek(chunk -> chunk.setRerankScore(score(query, canonical, weights, chunk)))
                .sorted(Comparator.comparing(RetrievedChunk::getRerankScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .filter(chunk -> seen.add(contextKey(chunk)))
                .limit(topK)
                .toList();
    }

    private double score(String query, String canonical, Map<String, Double> weights, RetrievedChunk chunk) {
        chunk.setEvidenceScore(0.0);
        AcademicScope scope = AcademicScope.resolve(query, null, null);
        if (scope.grade() != null && query.matches("(?s).*(培养|毕业|学分|课程|执行计划).*")) {
            // A dated curriculum request needs affirmative version evidence, not a generic NULL match.
            boolean versionMatches = scope.grade().equals(chunk.getTargetGrade())
                    || (chunk.getTargetGrade() == null && scope.grade().equals(AcademicScope.inferEnrollmentYear(chunk.getContent())));
            if (!versionMatches) return 0;
        }
        Map<String, Double> bodyWeights = new LinkedHashMap<>();
        weights.forEach((term, weight) -> { if (!text(chunk.getTitle()).contains(term)) bodyWeights.put(term, weight); });
        double lexical = coverage(bodyWeights.isEmpty() ? weights : bodyWeights, canonical, chunk.getContent());
        double title = coverage(weights, canonical, chunk.getTitle());
        double vector = normalized(chunk.getVectorScore());
        chunk.setEvidenceScore(0.8 * lexical + 0.2 * title);
        if (!EvidencePolicy.supports(query, chunk, 0)) { chunk.setEvidenceScore(0.0); return 0; }
        double fused = normalized(chunk.getNormalizedFusedScore());
        return Math.max(0, Math.min(1, 0.05 * fused + (0.25 * vector) + (0.60 * lexical) + (0.15 * title)
                - courseVariantPenalty(query, canonical, weights, chunk.getContent())));
    }

    private double coverage(Map<String, Double> weights, String canonical, String value) {
        String content = text(value);
        double[] possible = new double[canonical.length()];
        double[] covered = new double[canonical.length()];
        // Each query character contributes once, regardless of how many n-grams overlap it.
        for (var term : weights.entrySet()) {
            boolean hit = content.contains(term.getKey());
            for (int at = canonical.indexOf(term.getKey()); at >= 0; at = canonical.indexOf(term.getKey(), at + 1)) {
                for (int i = at; i < at + term.getKey().length(); i++) {
                    possible[i] = Math.max(possible[i], term.getValue());
                    if (hit) covered[i] = Math.max(covered[i], term.getValue());
                }
            }
        }
        double total = Arrays.stream(possible).sum();
        return total == 0 ? 0 : Arrays.stream(covered).sum() / total;
    }

    private double courseVariantPenalty(String query, String canonical, Map<String, Double> weights, String content) {
        if (!query.contains("学分") || query.contains("实验") || query.contains("课程设计")) return 0;
        var rows = COURSE_ROW.matcher(content == null ? "" : content);
        String bestName = "";
        double best = 0;
        while (rows.find()) {
            String name = rows.group(1);
            double match = coverage(weights, canonical, name);
            if (match > best) { best = match; bestName = name; }
        }
        return bestName.endsWith("实验") || bestName.endsWith("课程设计") ? 0.15 : 0;
    }

    private String text(String text) { return text == null ? "" : text.toLowerCase(Locale.ROOT); }

    private String contextKey(RetrievedChunk chunk) {
        return chunk.getDocId() + ":" + (chunk.getParentId() == null ? "c" + chunk.getChunkId() : "p" + chunk.getParentId());
    }

    private double normalized(Double score) {
        if (score == null || !Double.isFinite(score)) {
            return 0;
        }
        return Math.min(1.0, Math.max(0, score));
    }
}
