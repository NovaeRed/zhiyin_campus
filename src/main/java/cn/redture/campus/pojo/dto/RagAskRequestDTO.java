package cn.redture.campus.pojo.dto;

import lombok.Data;

@Data
public class RagAskRequestDTO {
    private Long sessionId;
    private String question;
    private String targetMajor;
    private Integer targetGrade;
    /**
     * 可选的用户/文档 cohort，用于在向量检索前路由到对应版本文档。
     */
    private String targetCohort;
}
