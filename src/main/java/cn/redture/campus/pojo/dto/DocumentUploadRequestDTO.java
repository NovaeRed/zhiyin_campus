package cn.redture.campus.pojo.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

@Data
public class DocumentUploadRequestDTO {
    @Size(max = 255)
    private String title;

    @Size(max = 32)
    private String version = "v1";

    @Pattern(regexp = "recursive|parent_child", message = "不支持的切片策略")
    private String strategy;

    /**
     * 文档业务类型，如 curriculum、recruitment、competition；未指定时按提取结果归类。
     */
    @Pattern(regexp = "[a-z][a-z0-9_-]{0,39}", message = "文档类型格式不正确")
    private String documentType;

    @Size(max = 100)
    private String targetMajor;

    @Min(1900)
    @Max(2200)
    private Integer targetGrade;

    /**
     * 年级/版本路由键，例如 "2024届" 或 "2025届"。
     */
    @Size(max = 32)
    private String targetCohort;

    @Size(max = 100)
    private String department;

    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "生效日期格式必须为YYYY-MM-DD")
    private String effectiveDate;
}
