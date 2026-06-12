package cn.redture.campus.rag.chunk;

import cn.redture.campus.config.RagProperties;

import java.util.ArrayList;
import java.util.List;

public class ParentChildChunker implements DocumentChunker {

    private final int parentSize;
    private final int childSize;
    private final int overlap;

    public ParentChildChunker(RagProperties properties) {
        this.parentSize = properties.getChunking().getParentSize();
        this.childSize = properties.getChunking().getChildSize();
        this.overlap = properties.getChunking().getOverlap();
    }

    @Override
    public String strategy() {
        return "parent_child";
    }

    @Override
    public List<ChunkUnit> split(String text) {
        List<ChunkUnit> result = new ArrayList<>();
        List<String> parents = RecursiveTextChunker.splitByStructure(RecursiveTextChunker.normalize(text), parentSize);
        for (int i = 0; i < parents.size(); i++) {
            String parent = parents.get(i);
            result.add(ChunkUnit.builder()
                    .content(parent)
                    .level("parent")
                    .tokenCount(RecursiveTextChunker.estimateTokens(parent))
                    .chunkType("text")
                    .build());
            for (String child : RecursiveTextChunker.splitWithOverlap(parent, childSize, overlap)) {
                result.add(ChunkUnit.builder()
                        .content(child)
                        .level("child")
                        .parentIndex(i)
                        .tokenCount(RecursiveTextChunker.estimateTokens(child))
                        .chunkType("text")
                        .build());
            }
        }
        return result;
    }
}
