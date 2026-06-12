package cn.redture.campus.pojo.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagSourceVO {
    private Long chunkId;
    private Long docId;
    private Long parentId;
    private String title;
    private String content;
    private double score;
    private String metadata;
}
