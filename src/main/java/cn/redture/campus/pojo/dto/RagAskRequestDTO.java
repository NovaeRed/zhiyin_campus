package cn.redture.campus.pojo.dto;

import lombok.Data;

@Data
public class RagAskRequestDTO {
    private Long sessionId;
    private String question;
    private String targetMajor;
    private Integer targetGrade;
}
