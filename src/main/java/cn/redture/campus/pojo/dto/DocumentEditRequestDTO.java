package cn.redture.campus.pojo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

/** Absent metadata preserves existing scope; a present object replaces its five editable fields. */
public record DocumentEditRequestDTO(
        @NotBlank @Size(max=255) String title,
        @NotBlank @Size(max=32) String version,
        @Valid Metadata metadata) {
    public record Metadata(@Size(max=100) String targetMajor,
                           @Min(1900) @Max(2200) Integer targetGrade,
                           @Pattern(regexp="20\\d{2}届") String targetCohort,
                           @Size(max=100) String department,
                           LocalDate effectiveDate) { }
}
