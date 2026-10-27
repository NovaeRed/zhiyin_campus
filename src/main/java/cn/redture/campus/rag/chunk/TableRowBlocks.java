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
