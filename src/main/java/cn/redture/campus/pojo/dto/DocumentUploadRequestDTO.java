package cn.redture.campus.pojo.dto;

import lombok.Data;

@Data
public class DocumentUploadRequestDTO {
    private String title;
    private String version = "v1";
    private String strategy;
    private String targetMajor;
    private Integer targetGrade;
    /**
     * 年级/版本路由键，例如 "2024届" 或 "2025届"。
     */
    private String targetCohort;
    private String department;
    private String effectiveDate;
}
