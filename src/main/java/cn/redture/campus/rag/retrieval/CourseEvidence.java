package cn.redture.campus.rag.retrieval;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Extracts typed course rows; values are never supplied by an LLM or hardcoded per question. */
public final class CourseEvidence {
    private static final Pattern ROW = Pattern.compile(
            "(?m)^\\s*(\\d{6,10})\\s+([^\\r\\n]+?)\\s+(\\d+(?:\\.\\d+)?)\\s+(20\\d{2})\\s*[-—]\\s*(20\\d{2})\\s+([12])(?:\\s|$)");
    private static final Pattern ASKED_YEAR = Pattern.compile("大([一二三四五])");
    private static final List<String> YEARS = List.of("大一", "大二", "大三", "大四", "大五");

    private CourseEvidence() { }

    public record Fact(String code, String name, BigDecimal credits, int startYear, int endYear,
                       int semester, int sourceIndex, Integer enrollmentYear, String nature) { }

    public static List<Fact> extract(List<RetrievedChunk> chunks) {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            var rows = ROW.matcher(content);
            while (rows.find()) {
                int start = Integer.parseInt(rows.group(4)), end = Integer.parseInt(rows.group(5));
                if (end != start + 1) continue;
                // Parent context contains several course rows. Bind nature to this row's block only.
                int blockStart = content.lastIndexOf("[TABLE_ROW ", rows.start());
                int blockEnd = blockStart < 0 ? -1 : content.indexOf("[/TABLE_ROW]", blockStart);
                String nature = null;
                if (blockStart >= 0 && blockEnd >= rows.end()) {
                    String block = content.substring(blockStart, blockEnd);
                    var label = Pattern.compile("课程性质[=＝]([^；\\r\\n]+)").matcher(block);
                    if (label.find()) nature = label.group(1).trim();
                    // Practical labs can explicitly belong to an elective group. "实践" alone is not "必修".
                    if ("实践".equals(nature)) {
                        var group = Pattern.compile("(?m)^分组上下文：([^\\r\\n]+)").matcher(block);
                        if (group.find()) {
                            String heading = group.group(1);
                            if (heading.contains("选修") && !heading.contains("必修")) nature = "选修（实践）";
                            else if (heading.contains("必修") && !heading.contains("选修")) nature = "必修（实践）";
                        }
                    }
                }
                facts.add(new Fact(rows.group(1), rows.group(2).trim(), new BigDecimal(rows.group(3)), start, end,
                        Integer.parseInt(rows.group(6)), i + 1, chunk.getTargetGrade(), nature));
            }
        }
        return facts;
    }

    public static Optional<String> answer(String question, List<RetrievedChunk> chunks) {
        if (!question.matches("(?s).*(学分|学年|学期|修读|安排|大[一二三四五]).*")) return Optional.empty();
        String normalized = normalize(question);
        List<Fact> matched = extract(chunks).stream().filter(f -> normalized.contains(normalize(f.name()))).toList();
        if (matched.isEmpty()) return Optional.empty();
        List<Fact> candidates = matched;
        matched = candidates.stream().filter(f -> independentlyMentioned(normalized, normalize(f.name()), candidates)).toList();
        Map<String, List<Fact>> courses = new LinkedHashMap<>();
        matched.stream().sorted(Comparator.comparingInt(f -> normalized.indexOf(normalize(f.name()))))
                .forEach(f -> courses.computeIfAbsent(normalize(f.name()), ignored -> new ArrayList<>()).add(f));
        List<String> answers = new ArrayList<>();
        for (var versions : courses.values()) {
            Fact first = versions.getFirst();
            if (versions.stream().anyMatch(f -> !f.code().equals(first.code()) || f.credits().compareTo(first.credits()) != 0
                    || f.startYear() != first.startYear() || f.endYear() != first.endYear() || f.semester() != first.semester()
                    || !Objects.equals(f.enrollmentYear(), first.enrollmentYear()))) {
                String citations = versions.stream().map(f -> "[" + f.sourceIndex() + "]").distinct().collect(java.util.stream.Collectors.joining());
                answers.add("《" + first.name() + "》存在不同版本的修读安排" + citations + "，请补充专业和入学年份以确定适用版本。");
            } else answers.add(render(question, first));
        }
        return Optional.of(String.join("\n", answers));
    }

    private static boolean independentlyMentioned(String question, String name, List<Fact> candidates) {
        for (int index = question.indexOf(name); index >= 0; index = question.indexOf(name, index + 1)) {
            boolean covered = false;
            for (Fact candidate : candidates) {
                String longer = normalize(candidate.name());
                if (longer.length() <= name.length()) continue;
                for (int start = question.indexOf(longer); start >= 0; start = question.indexOf(longer, start + 1)) {
                    if (start <= index && start + longer.length() >= index + name.length()) covered = true;
                }
            }
            if (!covered) return true;
        }
        return false;
    }

    private static String render(String question, Fact first) {
        Integer enrollment = AcademicScope.resolve(question, first.enrollmentYear(), null).grade();
        int relativeYear = enrollment == null ? 0 : first.startYear() - enrollment + 1;
        String yearName = relativeYear >= 1 && relativeYear <= YEARS.size() ? YEARS.get(relativeYear - 1) : null;
        String prefix = "";
        var asked = ASKED_YEAR.matcher(question);
        if (yearName != null && asked.find()) {
            String requested = "大" + asked.group(1);
            prefix = requested.equals(yearName) ? "安排在" + yearName + "。" : "不是" + requested + "，安排在" + yearName + "。";
        }
        String answer = prefix + "《" + first.name() + "》（课程号 " + first.code() + "）为 "
                + first.credits().stripTrailingZeros().toPlainString() + " 学分，建议在 " + first.startYear() + "-" + first.endYear()
                + "学年第" + first.semester() + "学期修读"
                + (yearName == null ? "" : "（" + yearName + (first.semester() == 1 ? "上学期" : "下学期") + "）")
                + " [" + first.sourceIndex() + "]。";
        return answer;
    }

    private static String normalize(String value) { return value.replaceAll("[\\s《》“”]+", ""); }
}
