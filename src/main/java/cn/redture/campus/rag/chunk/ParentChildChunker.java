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
        List<String> children = new ArrayList<>();
        for (String section : RecursiveTextChunker.splitByStructure(RecursiveTextChunker.normalize(text), parentSize)) {
            children.addAll(RecursiveTextChunker.splitWithOverlap(section, childSize, overlap));
        }
        int parentIndex = 0;
        for (var group : groupChildren(children)) {
            String parent = group.stream().map(children::get).collect(java.util.stream.Collectors.joining("\n\n"));
            result.add(ChunkUnit.builder()
                    .content(parent)
                    .level("parent")
                    .tokenCount(RecursiveTextChunker.estimateTokens(parent))
                    .chunkType(TableRowBlocks.contains(parent) ? "table" : "text")
                    .build());
            for (int index : group) {
                String child = children.get(index);
                result.add(ChunkUnit.builder()
                        .content(child)
                        .level("child")
                        .parentIndex(parentIndex)
                        .tokenCount(RecursiveTextChunker.estimateTokens(child))
                        .chunkType(TableRowBlocks.contains(child) ? "table" : "text")
                        .build());
            }
            parentIndex++;
        }
        return result;
    }

    /** Same page/section only; table records stay atomic even when longer than the size target. */
    public List<List<Integer>> groupChildren(List<String> children) {
        List<List<Integer>> groups = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int length = 0;
        String previous = null;
        for (int i = 0; i < children.size(); i++) {
            String child = children.get(i);
            var metadata = TableRowBlocks.metadata(child);
            var section = java.util.regex.Pattern.compile("(?m)^分组上下文：(.*)$").matcher(child);
            String boundary = metadata.isEmpty() ? "text" : metadata.get("source_page") + ":" + (section.find() ? section.group(1) : "");
            if (!current.isEmpty() && (length + 2 + child.length() > parentSize || !boundary.equals(previous))) {
                groups.add(List.copyOf(current)); current.clear(); length = 0;
            }
            current.add(i); length += child.length() + (current.size() > 1 ? 2 : 0); previous = boundary;
        }
        if (!current.isEmpty()) groups.add(List.copyOf(current));
        return groups;
    }
}
