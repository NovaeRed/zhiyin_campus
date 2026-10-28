package cn.redture.campus.rag.retrieval;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 多视角检索计划： 直接重写用于处理口语化表述，HyDE 用于桥接问题与文档表述风格， 退一步查询用于召回背景原则，扩展查询用于扩大检索覆盖范围。
 */
public record RetrievalQueryPlan(
    String originalQuery,
    String rewrittenQuery,
    String hydeDocument,
    String stepBackQuery,
    List<String> expandedQueries) {

    public static RetrievalQueryPlan fallback(String originalQuery) {
        return new RetrievalQueryPlan(originalQuery, originalQuery, null, null, List.of());
    }

    /**
     * 按改写、HyDE、后退字段、扩展和原问题的顺序去空白、去重，保留原问题用于向量召回。
     *
     * @param maxExpandedQueries 仅限制扩展查询数量，不限制所有查询的总数
     * @return 用于向量召回的有序检索表达列表
     */
    public List<String> effectiveQueries(int maxExpandedQueries) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        addIfText(queries, rewrittenQuery);
        addIfText(queries, hydeDocument);
        addIfText(queries, stepBackQuery);
        if (expandedQueries != null) {
            expandedQueries.stream()
                .limit(Math.max(0, maxExpandedQueries))
                .forEach(query -> addIfText(queries, query));
        }
        addIfText(queries, originalQuery);
        return new ArrayList<>(queries);
    }

    public String rerankQuery() {
        return StringUtils.hasText(rewrittenQuery) ? rewrittenQuery.trim() : originalQuery;
    }

    /**
     * 构造不含HyDE假设文本的关键词查询列表。
     *
     * @param maxExpandedQueries 关键词扩展查询的数量上限
     * @return 用于关键词召回的有序检索表达列表
     */
    public List<String> keywordQueries(int maxExpandedQueries) {
        return new RetrievalQueryPlan(
            originalQuery, rewrittenQuery, null, stepBackQuery, expandedQueries)
            .effectiveQueries(maxExpandedQueries);
    }

    private static void addIfText(LinkedHashSet<String> queries, String value) {
        if (StringUtils.hasText(value)) {
            queries.add(value.trim());
        }
    }
}
