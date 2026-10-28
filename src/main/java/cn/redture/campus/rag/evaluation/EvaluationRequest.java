package cn.redture.campus.rag.evaluation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * 按原始文档标注标准答案。
 */
public record EvaluationRequest(
    @NotBlank @Size(max = 100) String datasetVersion,
    @NotEmpty @Size(max = 10) List<@NotNull @Min(1) @Max(50) Integer> topKs,
    @NotEmpty @Size(max = 100) List<@NotNull @Valid Sample> samples) {

    public record Sample(
        @NotBlank @Size(max = 100) String id,
        @NotNull @Positive Long queryId,
        @NotBlank @Size(max = 4000) String question,
        boolean expectEmpty,
        @NotNull @Size(max = 50) List<@NotNull @Valid Evidence> evidence,
        @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") double minimumScore,
        @DecimalMin("0") @DecimalMax("1") double minimumRecall) {
    }

    /**
     * 所有已提供的筛选条件按“且”组合；引用字段填写原文证据。
     */
    public record Evidence(
        @NotBlank @Size(max = 100) String id,
        @Positive Long docId,
        @Positive Long chunkId,
        @Positive Long parentId,
        @NotBlank @Size(max = 255) String title,
        @Min(1) @Max(3) int relevance,
        @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 4000) String> quotes,
        @NotBlank @Size(max = 2000) String rationale) {
    }
}
