package cn.redture.campus.rag.evaluation;

import cn.redture.campus.rag.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import static cn.redture.campus.rag.evaluation.EvaluationReport.*;
import static cn.redture.campus.rag.evaluation.EvaluationRequest.*;

/** Deterministic evidence-based retrieval scoring. No LLM, database writes or network calls. */
@Component
public class RetrievalEvaluator {
    public EvaluationReport evaluate(EvaluationRequest request, Map<Long, RetrievalSnapshot> snapshots) {
        validate(request);
        List<CaseResult> cases = new ArrayList<>();
        for (Sample sample : request.samples()) {
            RetrievalSnapshot snapshot = snapshots.get(sample.queryId());
            if (snapshot == null || !sample.question().equals(snapshot.question())) {
                throw new IllegalArgumentException("Missing trace or question mismatch for sample " + sample.id());
            }
            for (int k : new TreeSet<>(request.topKs())) {
                cases.add(score(sample, snapshot, "FUSED", snapshot.fused(), k));
                cases.add(score(sample, snapshot, "SELECTED", snapshot.selected(), k));
            }
        }
        List<Summary> summaries = new ArrayList<>();
        for (String stage : List.of("FUSED", "SELECTED")) {
            for (int k : new TreeSet<>(request.topKs())) {
                List<CaseResult> group = cases.stream().filter(c -> c.stage().equals(stage) && c.k() == k).toList();
                summaries.add(new Summary(stage, k, group.size(), (int) group.stream().filter(c -> !c.expectEmpty()).count(),
                        mean(group, c -> c.metrics().score()), mean(group, c -> c.metrics().passed() ? 1.0 : 0.0),
                        mean(group, c -> c.metrics().precision()), nullableMean(group, c -> c.metrics().recall()),
                        nullableMean(group, c -> c.metrics().reciprocalRank()), nullableMean(group, c -> c.metrics().ndcg()),
                        nullableMean(group, c -> c.expectEmpty() ? null : c.metrics().recall() > 0 ? 1.0 : 0.0)));
            }
        }
        return new EvaluationReport(UUID.randomUUID().toString(), Instant.now().toString(), request.datasetVersion(),
                "evidence-v1: score=100*(0.4*recall+0.3*ndcg+0.2*precision+0.1*rr)", request, cases, summaries);
    }

    private CaseResult score(Sample sample, RetrievalSnapshot snapshot, String stage, List<RetrievedChunk> source, int k) {
        if (source == null) throw new IllegalArgumentException("Incomplete retrieval snapshot " + sample.queryId());
        List<RetrievedChunk> top = source.stream().limit(k).toList();
        Set<String> matched = new LinkedHashSet<>();
        Set<String> contexts = new HashSet<>();
        List<Match> matches = new ArrayList<>();
        int relevantCount = 0;
        double rr = 0, dcg = 0;
        for (int i = 0; i < top.size(); i++) {
            RetrievedChunk chunk = top.get(i);
            // Parent-context duplicates must not inflate scores even when child IDs differ.
            String contextKey = chunk.getDocId() + ":" + (chunk.getParentId() == null
                    ? "c" + chunk.getChunkId() : "p" + chunk.getParentId());
            boolean duplicate = !contexts.add(contextKey);
            Evidence evidence = duplicate ? null : sample.evidence().stream()
                    .filter(e -> !matched.contains(e.id()) && matches(e, chunk))
                    .sorted(Comparator.comparingInt(Evidence::relevance).reversed().thenComparing(Evidence::id))
                    .findFirst().orElse(null);
            String reason = duplicate ? "重复上下文，不重复计分" : "未匹配标注的文档及全部原文证据";
            int grade = 0;
            if (evidence != null) {
                matched.add(evidence.id());
                relevantCount++;
                grade = evidence.relevance();
                if (rr == 0) rr = 1.0 / (i + 1);
                dcg += (Math.pow(2, grade) - 1) / log2(i + 2);
                reason = "文档标识与全部原文引用匹配；" + evidence.rationale();
            }
            matches.add(new Match(i + 1, chunk, evidence == null ? null : evidence.id(), grade, reason));
        }
        List<String> missing = sample.evidence().stream().map(Evidence::id).filter(id -> !matched.contains(id)).toList();
        // Precision@K uses requested K, so a short result cannot claim full coverage.
        double precision = (double) relevantCount / k;
        Metrics metrics;
        if (sample.expectEmpty()) {
            double value = top.isEmpty() ? 100 : 0;
            metrics = new Metrics(precision, null, null, null, value, top.isEmpty());
        } else {
            double recall = (double) matched.size() / sample.evidence().size();
            List<Integer> ideal = sample.evidence().stream().map(Evidence::relevance)
                    .sorted(Comparator.reverseOrder()).limit(k).toList();
            double idcg = 0;
            for (int i = 0; i < ideal.size(); i++) idcg += (Math.pow(2, ideal.get(i)) - 1) / log2(i + 2);
            double ndcg = dcg / idcg;
            double value = 100 * (0.4 * recall + 0.3 * ndcg + 0.2 * precision + 0.1 * rr);
            metrics = new Metrics(precision, recall, rr, ndcg, value,
                    value + 1e-9 >= sample.minimumScore() && recall + 1e-9 >= sample.minimumRecall());
        }
        List<String> warnings = new ArrayList<>();
        if ("SELECTED".equals(stage) && k > snapshot.recordedTopK()) {
            warnings.add("K 超过原请求记录的 finalTopK=" + snapshot.recordedTopK() + "；无法模拟更大的召回预算");
        }
        if (top.size() < k) warnings.add("实际返回少于 K，Precision 分母仍为 K");
        if ("FUSED".equals(stage)) warnings.add("仅评估已记录候选池，不能恢复未召回块或各路原始排名");
        return new CaseResult(sample.id(), sample.queryId(), snapshot.traceId(), sample.question(), snapshot.rewrittenQuery(),
                stage, k, top.size(), sample.expectEmpty(), snapshot.recordedTopK(), snapshot.threshold(), warnings, metrics, missing, matches);
    }

    private boolean matches(Evidence evidence, RetrievedChunk chunk) {
        return (evidence.docId() == null || evidence.docId().equals(chunk.getDocId()))
                && (evidence.chunkId() == null || evidence.chunkId().equals(chunk.getChunkId()))
                && (evidence.parentId() == null || evidence.parentId().equals(chunk.getParentId()))
                && evidence.title().equals(chunk.getTitle())
                && evidence.quotes().stream().allMatch(q -> normalize(chunk.getContent()).contains(normalize(q)));
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s\\p{Z}]+", "");
    }

    private void validate(EvaluationRequest request) {
        if (request == null || request.samples() == null || request.samples().isEmpty() || request.samples().size() > 100
                || request.topKs() == null || request.topKs().isEmpty() || request.topKs().size() > 10
                || request.topKs().stream().anyMatch(k -> k == null || k < 1 || k > 50)) {
            throw new IllegalArgumentException("Expected 1..100 samples and 1..10 K values in 1..50");
        }
        Set<String> sampleIds = new HashSet<>();
        for (Sample sample : request.samples()) {
            if (sample == null || sample.id() == null || !sampleIds.add(sample.id()) || sample.evidence() == null
                    || sample.expectEmpty() != sample.evidence().isEmpty()
                    || !Double.isFinite(sample.minimumScore()) || sample.minimumScore() <= 0 || sample.minimumScore() > 100
                    || !Double.isFinite(sample.minimumRecall()) || sample.minimumRecall() < 0 || sample.minimumRecall() > 1
                    || (!sample.expectEmpty() && sample.minimumRecall() == 0)) {
                throw new IllegalArgumentException("Unique sample IDs required; answerable samples need evidence, empty cases must have none");
            }
            Set<String> ids = new HashSet<>();
            for (Evidence e : sample.evidence()) {
                if (e == null || e.id() == null || !ids.add(e.id()) || e.title() == null || e.title().isBlank()
                        || e.relevance() < 1 || e.relevance() > 3 || e.quotes() == null || e.quotes().isEmpty()
                        || e.quotes().stream().anyMatch(q -> q == null || normalize(q).isBlank())
                        || e.rationale() == null || e.rationale().isBlank()) {
                    throw new IllegalArgumentException("Evidence needs unique ID, title, grade 1..3, verbatim quotes and rationale");
                }
            }
        }
    }

    private double log2(double n) { return Math.log(n) / Math.log(2); }
    private double mean(List<CaseResult> cases, Function<CaseResult, Double> metric) {
        return cases.stream().mapToDouble(metric::apply).average().orElse(0);
    }
    private Double nullableMean(List<CaseResult> cases, Function<CaseResult, Double> metric) {
        var values = cases.stream().map(metric).filter(Objects::nonNull).mapToDouble(Double::doubleValue).average();
        return values.isPresent() ? values.getAsDouble() : null;
    }
}
