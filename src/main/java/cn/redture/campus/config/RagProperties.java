package cn.redture.campus.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "campus.rag")
public class RagProperties {

    private Storage storage = new Storage();
    private Qwen qwen = new Qwen();
    private Chunking chunking = new Chunking();
    private Retrieval retrieval = new Retrieval();

    @Data
    public static class Storage {
        private String provider = "pgvector";
        private String localFileDir = "./data/knowledge";
    }

    @Data
    public static class Qwen {
        private String apiKey;
        private String baseUrl;
        private String chatModel = "qwen-plus";
        /**
         * 稠密语义 embedding 模型，默认的 text-embedding-v4 适合中文校园政策 RAG 场景。
         */
        private String embeddingModel = "text-embedding-v4";
        private Integer embeddingDimension = 1024;
        private Float temperature = 0.2f;
        private Double topP = 0.8;
        private Integer maxTokens = 1600;
    }

    @Data
    public static class Chunking {
        private String strategy = "parent_child";
        private int chunkSize = 800;
        private int overlap = 120;
        private int parentSize = 1200;
        private int childSize = 400;
    }

    @Data
    public static class Retrieval {
        private int vectorTopK = 20;
        private int bm25TopK = 20;
        private int finalTopK = 5;
        private int rrfK = 60;
        private double rerankThreshold = 0.1;
        private double minimumEvidenceCoverage = 0.1;
        private boolean queryPlanningEnabled = true;
        /** Interpret colloquial academic intent against the actual curriculum catalog before routing. */
        private boolean semanticAcademicIntentEnabled = true;
        private int semanticAcademicIntentTimeoutSeconds = 20;
        private int maxExpandedQueries = 2;
    }
}
