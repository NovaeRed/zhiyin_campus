package cn.redture.campus.rag.retrieval;

import lombok.Data;

@Data
public class CurriculumDocument {
    private Long docId;
    private String title;
    private Integer targetGrade;
    private String targetMajor;
}
