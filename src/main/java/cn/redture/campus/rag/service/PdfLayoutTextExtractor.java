package cn.redture.campus.rag.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 按表头位置提取文本型 PDF，不借用相邻行的字段值。
 */
public final class PdfLayoutTextExtractor extends PDFTextStripper {
    private static final Pattern CODE = Pattern.compile("^(\\d{6,10})(?=\\D|$)");
    private static final Pattern YEAR = Pattern.compile("(20\\d{2})[-–—](20\\d{2})");
    private final List<TextPosition> positions = new ArrayList<>();
    private final StringBuilder output = new StringBuilder();
    private final List<String> issues = new ArrayList<>();
    private Header header;
    private float previousWidth;
    private int pages, detected, accepted;
    private boolean hasText;
    private String section = "";

    public record Result(
        String text, int pages, int detectedRows, int acceptedRows, List<String> issues) {
    }

    private record Line(float y, List<TextPosition> glyphs) {
    }

    private record Header(
        float codeLeft,
        float codeRight,
        float credits,
        float year,
        float semester,
        float afterSemester,
        Float nature,
        Float category) {
    }

    public PdfLayoutTextExtractor() throws IOException {
        setSortByPosition(true);
    }

    public static Result extract(Path path) throws IOException {
        try (PDDocument document = PDDocument.load(path.toFile())) {
            var extractor = new PdfLayoutTextExtractor();
            extractor.getText(document);
            if (!extractor.hasText) {
                throw new IOException("PDF has no extractable text; OCR/manual review is required");
            }
            return new Result(
                extractor.output.toString(),
                extractor.pages,
                extractor.detected,
                extractor.accepted,
                List.copyOf(extractor.issues));
        }
    }

    @Override
    protected void startPage(PDPage page) throws IOException {
        super.startPage(page);
        positions.clear();
        // 仅在页面尺寸相同时沿用列布局，课程行的字段值不跨页拼接。
        float width = page.getCropBox().getWidth();
        if (Math.abs(width - previousWidth) > 1) {
            header = null;
        }
        previousWidth = width;
        pages++;
    }

    @Override
    protected void writeString(String text, List<TextPosition> glyphs) {
        positions.addAll(glyphs);
    }

    @Override
    protected void endPage(PDPage page) throws IOException {
        List<Line> lines = lines(positions);
        boolean readable = positions.stream().anyMatch(p -> !p.getUnicode().isBlank());
        if (readable) {
            hasText = true;
        } else {
            issues.add("page=" + pages + ": no text layer; OCR/manual review required");
        }
        Map<Integer, Header> schemas = new HashMap<>();
        Map<Integer, String> contexts = new HashMap<>();
        List<Integer> anchors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            String lineText = render(line.glyphs());
            if (lineText.matches("^[（(]?\\d{1,2}[.．、)）].*")
                && lineText.matches(".*[\\p{IsHan}].*")
                && lineText.length() < 120) {
                section = lineText;
            }
            Header found = header(line);
            if (found != null) {
                header = found;
            } else if (header != null
                && !line.glyphs().isEmpty()
                && Math.abs(line.glyphs().getFirst().getXDirAdj() - header.codeLeft()) < 8) {
                var code = CODE.matcher(cell(line.glyphs(), 0, header.credits()));
                if (code.find()) {
                    anchors.add(i);
                    schemas.put(
                        i,
                        new Header(
                            header.codeLeft(),
                            end(line.glyphs(), code.group(1)) + 0.1f,
                            header.credits(),
                            header.year(),
                            header.semester(),
                            header.afterSemester(),
                            header.nature(),
                            header.category()));
                    contexts.put(i, section);
                }
            }
            if (found == null
                && !schemas.containsKey(i)
                && CODE.matcher(cell(line.glyphs(), 0, Float.MAX_VALUE)).find()
                && YEAR.matcher(cell(line.glyphs(), 0, Float.MAX_VALUE)).find()) {
                issues.add(
                    "page="
                        + pages
                        + ": course-like row without a reliable column schema: "
                        + lineText);
            }
        }
        Map<Integer, String> replacements = new HashMap<>();
        Set<Integer> consumed = new HashSet<>();
        int row = 0;
        for (int anchorNumber = 0; anchorNumber < anchors.size(); anchorNumber++) {
            int index = anchors.get(anchorNumber);
            detected++;
            row++;
            Line anchor = lines.get(index);
            Header schema = schemas.get(index);
            int previous = anchorNumber - 1, next = anchorNumber + 1;
            float top = anchor.y() - 12;
            float bottom = anchor.y() + 32;
            if (previous >= 0) {
                top = Math.max(top, (lines.get(anchors.get(previous)).y() + anchor.y()) / 2);
            }
            if (next < anchors.size()) {
                float nextY = lines.get(anchors.get(next)).y();
                bottom = Math.min(bottom, Math.max(nextY - 12, (nextY + anchor.y()) / 2));
            }
            List<Line> region = new ArrayList<>();
            for (Line line : lines) {
                if (line.y() >= top
                    && line.y() < bottom
                    && header(line) == null
                    && !render(line.glyphs()).matches("^[（(]?\\d{1,2}[.．、)）].*")) {
                    region.add(line);
                }
            }
            String code = cell(anchor.glyphs(), 0, schema.codeRight());
            StringBuilder name = new StringBuilder();
            for (Line line : region) {
                String part = cell(line.glyphs(), schema.codeRight(), schema.credits());
                if (line == anchor || part.matches(".*\\p{IsHan}.*")) {
                    name.append(part);
                }
            }
            String credits = cell(anchor.glyphs(), schema.credits(), schema.year());
            String year = cell(anchor.glyphs(), schema.year(), schema.semester());
            String semester = cell(anchor.glyphs(), schema.semester(), schema.afterSemester());
            var years = YEAR.matcher(year);
            boolean valid =
                !name.isEmpty()
                    && name.length() <= 100
                    && name.indexOf("\uFFFD") < 0
                    && credits.matches("\\d{1,2}(?:\\.\\d+)?")
                    && years.matches()
                    && Integer.parseInt(years.group(2))
                    == Integer.parseInt(years.group(1)) + 1
                    && semester.matches("[12]");
            StringBuilder block =
                new StringBuilder(
                    "[TABLE_ROW page="
                        + pages
                        + " row="
                        + row
                        + " status="
                        + (valid ? "validated" : "review")
                        + "]\n");
            if (!contexts.get(index).isBlank()) {
                block.append("分组上下文：").append(contexts.get(index)).append('\n');
            }
            if (valid) {
                accepted++;
                block.append(code)
                    .append(' ')
                    .append(name)
                    .append(' ')
                    .append(credits)
                    .append(' ')
                    .append(year.replace('–', '-').replace('—', '-'))
                    .append(' ')
                    .append(semester)
                    .append('\n');
                block.append("字段：课程号=")
                    .append(code)
                    .append("；课程名称=")
                    .append(name)
                    .append("；学分=")
                    .append(credits)
                    .append("；建议修读学年=")
                    .append(year)
                    .append("；学期=")
                    .append(semester)
                    .append('\n');
                if (schema.nature() != null && schema.category() != null) {
                    block.append("考试方式=")
                        .append(cell(anchor.glyphs(), schema.afterSemester(), schema.nature()))
                        .append("；课程性质=")
                        .append(cell(anchor.glyphs(), schema.nature(), schema.category()))
                        .append("；课程类别=")
                        .append(cell(anchor.glyphs(), schema.category(), Float.MAX_VALUE))
                        .append('\n');
                }
            } else {
                issues.add(
                    "page="
                        + pages
                        + ", row="
                        + row
                        + ", code="
                        + code
                        + ": incomplete/ambiguous cells");
                block.append("待复核：本行字段不完整，禁止据此推断课程数值。\n");
            }
            // 保留全部原始字符与列，包括英文名称和可选列，便于审计。
            for (Line line : region) {
                block.append("> ").append(render(line.glyphs())).append('\n');
                consumed.add(lines.indexOf(line));
            }
            block.append("[/TABLE_ROW]");
            replacements.put(index, block.toString());
        }
        output.append("\n[PDF_PAGE ").append(pages).append("]\n");
        for (int i = 0; i < lines.size(); i++) {
            if (replacements.containsKey(i)) {
                output.append(replacements.get(i)).append("\n\n");
            } else if (!consumed.contains(i)) {
                output.append(render(lines.get(i).glyphs())).append('\n');
            }
        }
        super.endPage(page);
    }

    private static List<Line> lines(List<TextPosition> glyphs) {
        List<TextPosition> sorted =
            glyphs.stream()
                .sorted(
                    Comparator.comparing(TextPosition::getYDirAdj)
                        .thenComparing(TextPosition::getXDirAdj))
                .toList();
        List<Line> result = new ArrayList<>();
        for (TextPosition glyph : sorted) {
            if (result.isEmpty() || Math.abs(glyph.getYDirAdj() - result.getLast().y()) > 1.8) {
                result.add(new Line(glyph.getYDirAdj(), new ArrayList<>()));
            }
            result.getLast().glyphs().add(glyph);
        }
        result.forEach(line -> line.glyphs().sort(Comparator.comparing(TextPosition::getXDirAdj)));
        return result;
    }

    private static Header header(Line line) {
        List<TextPosition> glyphs = line.glyphs();
        String text = cell(glyphs, 0, Float.MAX_VALUE);
        boolean english = text.contains("AcademicYear") && text.contains("Semester");
        if (!english
            && (!text.contains("课程号") || !text.contains("建议修读学年") || !text.contains("学期"))) {
            return null;
        }
        Float codeEnd = end(glyphs, english ? "Code" : "课程号"),
            credits = start(glyphs, english ? "Credits" : "学分"),
            year = start(glyphs, english ? "AcademicYear" : "建议修读学年"),
            semester = start(glyphs, english ? "Semester" : "学期"),
            after = start(glyphs, english ? "Exam" : "考试方式");
        if (codeEnd == null || credits == null || year == null || semester == null) {
            return null;
        }
        if (!(codeEnd < credits && credits < year && year < semester)) {
            return null;
        }
        return new Header(
            start(glyphs, english ? "Code" : "课程号"),
            codeEnd + 3,
            credits - 2,
            year - 2,
            semester - 2,
            after == null ? semester + 25 : after - 2,
            boundary(glyphs, "课程性质"),
            boundary(glyphs, "课程类别"));
    }

    private static Float boundary(List<TextPosition> glyphs, String label) {
        Float value = start(glyphs, label);
        return value == null ? null : value - 2;
    }

    private static Float start(List<TextPosition> glyphs, String label) {
        return locate(glyphs, label, false);
    }

    private static Float end(List<TextPosition> glyphs, String label) {
        return locate(glyphs, label, true);
    }

    private static Float locate(List<TextPosition> glyphs, String label, boolean end) {
        StringBuilder text = new StringBuilder();
        List<TextPosition> chars = new ArrayList<>();
        for (TextPosition glyph : glyphs) {
            for (char c : glyph.getUnicode().toCharArray()) {
                if (!Character.isWhitespace(c)) {
                    text.append(c);
                    chars.add(glyph);
                }
            }
        }
        int index = text.indexOf(label);
        if (index < 0) {
            return null;
        }
        TextPosition glyph = chars.get(index + (end ? label.length() - 1 : 0));
        return glyph.getXDirAdj() + (end ? glyph.getWidthDirAdj() : 0);
    }

    private static String cell(List<TextPosition> glyphs, float left, float right) {
        StringBuilder text = new StringBuilder();
        for (TextPosition glyph : glyphs) {
            if (glyph.getXDirAdj() >= left && glyph.getXDirAdj() < right) {
                text.append(glyph.getUnicode());
            }
        }
        return text.toString().replaceAll("\\s+", "").trim();
    }

    private static String render(List<TextPosition> glyphs) {
        StringBuilder text = new StringBuilder();
        float end = -1;
        for (TextPosition glyph : glyphs) {
            if (end >= 0 && glyph.getXDirAdj() - end > 2) {
                text.append("  ");
            }
            text.append(glyph.getUnicode());
            end = glyph.getXDirAdj() + glyph.getWidthDirAdj();
        }
        return text.toString().trim();
    }
}
