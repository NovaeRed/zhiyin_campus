package cn.redture.campus.rag.repository;

import lombok.Data;

@Data
public class KnowledgeDocumentInsert {
    private Long id;
    private String title;
    private String filePath;
    private String fileType;
    private String version;
    private String metadataJson;
    private String chunkStrategyJson;
    private Long createdBy;
}
