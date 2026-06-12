package cn.redture.campus.rag.chunk;

import cn.redture.campus.config.RagProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
public class ChunkerRouter {

    private final RagProperties properties;
    private final Map<String, DocumentChunker> chunkers;

    public ChunkerRouter(RagProperties properties) {
        this.properties = properties;
        this.chunkers = Map.of(
                "recursive", new RecursiveTextChunker(properties),
                "parent_child", new ParentChildChunker(properties)
        );
    }

    public DocumentChunker resolve(String requestedStrategy, String fileType) {
        String strategy = StringUtils.hasText(requestedStrategy) ? requestedStrategy : autoStrategy(fileType);
        return chunkers.getOrDefault(strategy, chunkers.get(properties.getChunking().getStrategy()));
    }

    private String autoStrategy(String fileType) {
        if ("md".equalsIgnoreCase(fileType) || "html".equalsIgnoreCase(fileType)) {
            return "recursive";
        }
        return properties.getChunking().getStrategy();
    }
}
