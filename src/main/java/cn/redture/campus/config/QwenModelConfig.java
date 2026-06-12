package cn.redture.campus.config;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;

import java.util.List;

@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class QwenModelConfig {

    @Bean
    @Lazy
    public ChatModel qwenChatModel(RagProperties properties) {
        RagProperties.Qwen qwen = properties.getQwen();
        String apiKey = resolveApiKey(qwen);
        QwenChatModel.QwenChatModelBuilder builder = QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName(qwen.getChatModel())
                .temperature(qwen.getTemperature())
                .topP(qwen.getTopP())
                .maxTokens(qwen.getMaxTokens());
        if (StringUtils.hasText(qwen.getBaseUrl())) {
            builder.baseUrl(qwen.getBaseUrl());
        }
        return builder.build();
    }

    @Bean
    @Lazy
    public EmbeddingModel qwenEmbeddingModel(RagProperties properties) {
        RagProperties.Qwen qwen = properties.getQwen();
        String apiKey = resolveApiKey(qwen);
        QwenEmbeddingModel.QwenEmbeddingModelBuilder builder = QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(qwen.getEmbeddingModel())
                .dimension(qwen.getEmbeddingDimension());
        if (StringUtils.hasText(qwen.getBaseUrl())) {
            builder.baseUrl(qwen.getBaseUrl());
        }
        return builder.build();
    }

    private String resolveApiKey(RagProperties.Qwen qwen) {
        if (StringUtils.hasText(qwen.getApiKey())) {
            return qwen.getApiKey();
        }
        for (String candidate : List.of("Qwen-Api", "QWEN_API", "QWEN_API_KEY", "DASHSCOPE_API_KEY")) {
            String value = System.getenv(candidate);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        throw new IllegalStateException("DashScope api key is missing. Please set environment variable Qwen-Api or QWEN_API_KEY.");
    }
}
