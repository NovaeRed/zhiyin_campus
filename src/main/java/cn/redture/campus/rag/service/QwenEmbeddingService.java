package cn.redture.campus.rag.service;

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

    public QwenEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.embeddingModelProvider = embeddingModelProvider;
    }

    public float[] embedQuery(String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Embedding query cannot be blank");
        }
        return embeddingModel().embed(text).content().vector();
    }

    public List<float[]> embedDocuments(List<String> texts) {
        List<String> validTexts = texts == null ? List.of() : texts.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (validTexts.isEmpty()) {
            return new ArrayList<>();
        }
        List<TextSegment> segments = validTexts.stream().map(TextSegment::from).toList();
        return embeddingModel().embedAll(segments).content().stream()
                .map(Embedding::vector)
                .toList();
    }

    private EmbeddingModel embeddingModel() {
        return embeddingModelProvider.getObject();
    }
}
