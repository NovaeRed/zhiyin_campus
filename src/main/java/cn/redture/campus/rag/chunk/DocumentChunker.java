package cn.redture.campus.rag.chunk;

import java.util.List;

public interface DocumentChunker {
    String strategy();

    List<ChunkUnit> split(String text);
}
