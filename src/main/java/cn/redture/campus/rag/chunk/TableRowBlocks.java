package cn.redture.campus.rag.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 完整表格行是不可拆分的记录，即使超过目标切片长度也不截断。
 */
public final class TableRowBlocks {
    private static final Pattern ROW =
        Pattern.compile(
            "\\[TABLE_ROW page=(\\d+) row=(\\d+) status=(validated|review)]\\n"
                + ".*?\\[/TABLE_ROW]",
            Pattern.DOTALL);

    private TableRowBlocks() {
    }

    public record Piece(String text, boolean table) {
    }

    /**
     * 将完整课程行标记区块作为不可分割片段，与相邻普通正文按原始顺序拆开。
     *
     * @param text 可能同时包含普通正文和课程行标记的文本
     * @return 正文片段及其是否属于原子表格记录的标记
     */
    public static List<Piece> pieces(String text) {
        List<Piece> pieces = new ArrayList<>();
        var matcher = ROW.matcher(text);
        int offset = 0;
        while (matcher.find()) {
            if (matcher.start() > offset) {
                pieces.add(new Piece(text.substring(offset, matcher.start()), false));
            }
            pieces.add(new Piece(matcher.group(), true));
            offset = matcher.end();
        }
        if (offset < text.length()) {
            pieces.add(new Piece(text.substring(offset), false));
        }
        return pieces;
    }

    public static boolean contains(String text) {
        return ROW.matcher(text).find();
    }

    /**
     * 从首个完整课程行标记中读取页码、行号及校验信息，供切片来源追踪使用。
     *
     * @param text 可能包含课程行标记的切片正文
     * @return 课程行来源与校验元数据；未找到完整标记时返回空映射
     */
    public static Map<String, Object> metadata(String text) {
        var matcher = ROW.matcher(text);
        if (!matcher.find()) {
            return Map.of();
        }
        return Map.of(
            "source_page",
            Integer.parseInt(matcher.group(1)),
            "source_row",
            Integer.parseInt(matcher.group(2)),
            "table_validation",
            matcher.group(3),
            "extraction_version",
            "pdf-layout-v1");
    }
}
