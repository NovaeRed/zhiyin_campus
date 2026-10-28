package cn.redture.campus.rag.service;

import cn.redture.campus.config.RagProperties;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class QwenEmbeddingService {

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final RagProperties properties;

    public QwenEmbeddingService(
        ObjectProvider<EmbeddingModel> embeddingModelProvider, RagProperties properties) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.properties = properties;
    }

    /**
     * 为非空检索文本生成查询向量并校验维度与当前配置一致。
     *
     * @param text 用于向量召回的检索文本
     * @return 维度已校验的查询向量
     */
    public float[] embedQuery(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Embedding query cannot be blank");
        }
        return validateDimension(embeddingModel().embed(text).content().vector());
    }

    /**
     * 去除空白文本后按剩余顺序批量生成子块向量；调用方应预先过滤输入并核对向量数量。
     *
     * @param texts 待生成向量的子块正文列表
     * @return 与有效文本顺序一致的向量列表，有效文本为空时返回空列表
     */
    public List<float[]> embedDocuments(List<String> texts) {
        List<String> validTexts =
            texts == null
                ? List.of()
                : texts.stream().filter(StringUtils::hasText).map(String::trim).toList();
        if (validTexts.isEmpty()) {
            return new ArrayList<>();
        }
        List<TextSegment> segments = validTexts.stream().map(TextSegment::from).toList();
        return embeddingModel().embedAll(segments).content().stream()
            .map(Embedding::vector)
            .map(this::validateDimension)
            .toList();
    }

    private EmbeddingModel embeddingModel() {
        return embeddingModelProvider.getObject();
    }

    /**
     * 校验模型返回的向量长度与配置维度一致。
     *
     * @param vector 模型返回的待校验向量
     * @return 校验通过的原向量对象
     */
    private float[] validateDimension(float[] vector) {
        int expected = properties.getQwen().getEmbeddingDimension();
        if (vector == null || vector.length != expected) {
            throw new IllegalStateException(
                "Embedding dimension mismatch. model="
                    + properties.getQwen().getEmbeddingModel()
                    + ", expected="
                    + expected
                    + ", actual="
                    + (vector == null ? 0 : vector.length));
        }
        return vector;
    }
}
