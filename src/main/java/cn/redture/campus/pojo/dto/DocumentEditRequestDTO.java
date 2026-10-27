package cn.redture.campus.pojo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;

/**
 * 未提供元数据时保留现有范围；提供元数据对象时替换其中五个可编辑字段。
 */
public record DocumentEditRequestDTO(
    @NotBlank @Size(max = 255) String title,
    @NotBlank @Size(max = 32) String version,
    @Valid Metadata metadata) {
    public record Metadata(
        @Size(max = 100) String targetMajor,
        @Min(1900) @Max(2200) Integer targetGrade,
        @Pattern(regexp = "20\\d{2}届") String targetCohort,
        @Size(max = 100) String department,
        LocalDate effectiveDate) {
    }
}
