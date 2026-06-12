package cn.redture.campus.pojo.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RagAnswerResponseVO {
    private Long sessionId;
    private Long queryId;
    private String traceId;
    private String originalQuery;
    private String rewrittenQuery;
    private String answer;
    private List<RagSourceVO> sources;
}
