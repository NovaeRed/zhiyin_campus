package cn.redture.campus.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleContentRerankerTest {

    private final SimpleContentReranker reranker = new SimpleContentReranker();

    @Test
    void reranksChineseMatchesAheadOfUnrelatedChunks() {
        RetrievedChunk unrelated = RetrievedChunk.builder()
                .chunkId(1L)
                .content("本系统支持订单统计和消息通知。")
                .fusedScore(0.01)
                .build();
        RetrievedChunk related = RetrievedChunk.builder()
                .chunkId(2L)
                .content("核心能力包括风险识别、准入审核、骑手健康证校验。")
                .fusedScore(0.01)
                .build();

        List<RetrievedChunk> result = reranker.rerank("如何进行风险识别和准入审核？",
                List.of(unrelated, related), 2);

        assertThat(result).extracting(RetrievedChunk::getChunkId).containsExactly(2L, 1L);
    }

    @Test
    void scoresVectorRetrievedChineseChunksAboveDefaultThreshold() {
        RetrievedChunk retrieved = RetrievedChunk.builder()
                .chunkId(8L)
                .content("""
                        校园外卖守护系统全角色原型设计稿。
                        该系统划分为学生/外卖员端、宿管端、管理员端、核心服务层及外部接口层。
                        """)
                .vectorScore(0.57)
                .fusedScore(0.014)
                .build();

        List<RetrievedChunk> result = reranker.rerank("这外卖守护平台的角色分层应该是怎样的",
                List.of(retrieved), 1);

        assertThat(result.getFirst().getRerankScore()).isGreaterThanOrEqualTo(0.1);
    }

    @Test
    void defaultThresholdDoesNotDropAllRetrievedRelevantChunks() {
        List<RetrievedChunk> fused = List.of(
                RetrievedChunk.builder()
                        .chunkId(8L)
                        .content("校园外卖守护系统全角色原型设计稿，包含学生、外卖员、宿管、管理员等角色。")
                        .vectorScore(0.46)
                        .fusedScore(0.013)
                        .build(),
                RetrievedChunk.builder()
                        .chunkId(10L)
                        .content("系统划分为学生/外卖员端、宿管端、管理员端、核心服务层及外部接口层。")
                        .vectorScore(0.56)
                        .fusedScore(0.014)
                        .build()
        );

        List<RetrievedChunk> selected = reranker.rerank("这外卖守护平台的角色分层应该是怎样的", fused, 5)
                .stream()
                .filter(chunk -> chunk.getRerankScore() >= 0.1)
                .toList();

        assertThat(selected).isNotEmpty();
    }
}
