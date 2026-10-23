package cn.redture.campus.rag.evaluation;

import lombok.Data;

@Data
public class RecordedRetrieval {
    private Long queryId;
    private String traceId;
    private String question;
    private String rewrittenQuery;
    private Integer recordedTopK;
    private Double threshold;
    private String fusedJson;
    private String selectedJson;
}
