package cn.redture.campus.rag.repository;

import lombok.Data;

@Data
public class RagQueryInsert {
    private Long id;
    private Long sessionId;
    private String traceId;
    private String originalQuery;
    private String rewrittenQuery;
}
