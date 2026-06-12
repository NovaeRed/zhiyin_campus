package cn.redture.campus.rag.retrieval;

import lombok.Builder;
import lombok.Data;
import lombok.AllArgsConstructor;
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
    private String metadata;
    private Double vectorScore;
    private Double bm25Score;
    private Double fusedScore;
    private Double rerankScore;
}
