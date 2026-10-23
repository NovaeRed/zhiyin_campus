package cn.redture.campus.pojo.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
@lombok.extern.jackson.Jacksonized
public class RagSourceVO {
    private Long chunkId;
    private Long docId;
    private Long parentId;
    private String title;
    private String content;
    private double score;
    private String metadata;
}
