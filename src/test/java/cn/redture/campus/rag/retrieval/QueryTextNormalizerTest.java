package cn.redture.campus.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QueryTextNormalizerTest {

    @Test
    void extractsChineseKeywordsInsteadOfKeepingTheWholeQuestion() {
        List<String> keywords = QueryTextNormalizer.keywords("校园外卖守护系统有哪些核心模块？");

        assertThat(keywords)
                .contains("校园外卖守护系统", "外卖守护", "核心模块")
                .doesNotContain("校园外卖守护系统有哪些核心模块");
    }

    @Test
    void termsSupportRerankingByChineseKeywordOverlap() {
        Set<String> terms = QueryTextNormalizer.terms("如何进行风险识别和准入审核？");

        assertThat(terms).contains("风险识别", "准入审核");
    }
}
