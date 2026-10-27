package cn.redture.campus.rag.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedChunk {
    private Long chunkId;
    private Long docId;
    private Long parentId;
    private String title;
    private String content;
    private String matchedContent;
    private Integer targetGrade;
    private String metadata;
    private Double vectorScore;
    private Double bm25Score;
    private Double fusedScore;
    private Double normalizedFusedScore;
    private Double evidenceScore;
    private Double rerankScore;
}
