package cn.redture.campus.rag.repository;

import lombok.Data;

@Data
public class DocChunkInsert {
    private Long id;
    private Long docId;
    private Long parentId;
    private String level;
    private String content;
    private String embeddingVector;
    private String metadataJson;
    private String targetMajor;
    private Integer targetGrade;
}
