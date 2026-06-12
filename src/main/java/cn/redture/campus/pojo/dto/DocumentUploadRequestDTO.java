package cn.redture.campus.pojo.dto;

import lombok.Data;

@Data
public class DocumentUploadRequestDTO {
    private String title;
    private String version = "v1";
    private String strategy;
    private String targetMajor;
    private Integer targetGrade;
    private String department;
    private String effectiveDate;
}
