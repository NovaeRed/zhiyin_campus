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
    private String requestId;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String conversationHistory;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Integer academicStudyYear;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Integer academicSemester;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String academicCourseNature;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.util.function.Consumer<String> tokenConsumer;
}
