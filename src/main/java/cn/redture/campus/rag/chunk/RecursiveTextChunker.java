package cn.redture.campus.rag.chunk;

import cn.redture.campus.config.RagProperties;

import java.util.ArrayList;
import java.util.List;

public class RecursiveTextChunker implements DocumentChunker {

    private final int chunkSize;
    private final int overlap;

    public RecursiveTextChunker(RagProperties properties) {
        this.chunkSize = properties.getChunking().getChunkSize();
        this.overlap = properties.getChunking().getOverlap();
    }

    @Override
    public String strategy() {
        return "recursive";
    }

    @Override
    public List<ChunkUnit> split(String text) {
        List<String> sections = splitByStructure(normalize(text), chunkSize);
        List<ChunkUnit> chunks = new ArrayList<>();
        for (String section : sections) {
            for (String piece : splitWithOverlap(section, chunkSize, overlap)) {
                chunks.add(
                    ChunkUnit.builder()
                        .content(piece)
                        .level("child")
                        .tokenCount(estimateTokens(piece))
                        .chunkType(TableRowBlocks.contains(piece) ? "table" : "text")
                        .build());
            }
        }
        return chunks;
    }

    /**
     * 优先保留完整表格记录，普通正文按段落合并，超长段落再按句子拆分。
     *
     * @param text      已规范换行的正文或片段
     * @param maxLength 普通文本的目标字符长度，原子表格记录可超过此值
     * @return 保持原始顺序的结构片段
     */
    static List<String> splitByStructure(String text, int maxLength) {
        if (TableRowBlocks.contains(text)) {
            List<String> rows = new ArrayList<>();
            for (var piece : TableRowBlocks.pieces(text)) {
                if (piece.table()) {
                    rows.add(piece.text());
                } else {
                    rows.addAll(splitByStructure(piece.text(), maxLength));
                }
            }
            return rows;
        }
        List<String> result = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (current.length() + trimmed.length() + 2 <= maxLength) {
                if (!current.isEmpty()) {
                    current.append("\n\n");
                }
                current.append(trimmed);
            } else {
                flush(current, result);
                if (trimmed.length() <= maxLength) {
                    current.append(trimmed);
                } else {
                    result.addAll(splitSentences(trimmed, maxLength));
                }
            }
        }
        flush(current, result);
        return result;
    }

    /**
     * 对普通文本按字符窗口切分并保留重叠；表格记录整块通过，不截断课程字段。
     *
     * @param text      待按长度切分的结构片段
     * @param maxLength 普通窗口的最大目标字符数
     * @param overlap   相邻普通窗口期望重叠的字符数
     * @return 保留原子表格记录的有序文本窗口
     */
    static List<String> splitWithOverlap(String text, int maxLength, int overlap) {
        if (TableRowBlocks.contains(text)) {
            List<String> rows = new ArrayList<>();
            for (var piece : TableRowBlocks.pieces(text)) {
                if (piece.table()) {
                    rows.add(piece.text());
                } else {
                    rows.addAll(splitWithOverlap(piece.text(), maxLength, overlap));
                }
            }
            return rows;
        }
        List<String> result = new ArrayList<>();
        String source = text.trim();
        if (source.length() <= maxLength) {
            result.add(source);
            return result;
        }
        int start = 0;
        while (start < source.length()) {
            int end = Math.min(start + maxLength, source.length());
            result.add(source.substring(start, end).trim());
            if (end == source.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return result;
    }

    /**
     * 按正文字符长度估算存储用的令牌数量。
     *
     * @param text 需要估算长度的切片正文
     * @return 至少为一的估算令牌数
     */
    static int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 1.8));
    }

    /**
     * 统一正文换行并移除纯页码标记，保留课程行内部的页行元数据。
     *
     * @param text 提取器输出的原始正文，可为空
     * @return 可继续结构切分的规范化正文
     */
    static String normalize(String text) {
        return text == null
            ? ""
            : text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("(?m)^[\\t ]*\\[PDF_PAGE \\d+][\\t ]*$", "")
            .trim();
    }

    private static List<String> splitSentences(String text, int maxLength) {
        List<String> result = new ArrayList<>();
        String[] sentences = text.split("(?<=[。！？!?；;\\.])");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() + sentence.length() <= maxLength) {
                current.append(sentence);
            } else {
                flush(current, result);
                if (sentence.length() <= maxLength) {
                    current.append(sentence);
                } else {
                    result.addAll(splitWithOverlap(sentence, maxLength, 0));
                }
            }
        }
        flush(current, result);
        return result;
    }

    private static void flush(StringBuilder current, List<String> result) {
        if (!current.isEmpty()) {
            result.add(current.toString().trim());
            current.setLength(0);
        }
    }
}
