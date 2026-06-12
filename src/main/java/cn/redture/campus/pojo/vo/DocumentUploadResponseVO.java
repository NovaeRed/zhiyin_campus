package cn.redture.campus.pojo.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentUploadResponseVO {
    private Long docId;
    private String title;
    private String fileType;
    private String strategy;
    private int parentChunks;
    private int childChunks;
    private String status;
}
