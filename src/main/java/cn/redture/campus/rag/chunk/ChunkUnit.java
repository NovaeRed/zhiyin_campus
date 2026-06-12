package cn.redture.campus.rag.chunk;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChunkUnit {
    private String content;
    private String level;
    private Integer parentIndex;
    private int tokenCount;
    private String chunkType;
}
