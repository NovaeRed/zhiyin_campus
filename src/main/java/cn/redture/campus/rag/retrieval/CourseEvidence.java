package cn.redture.campus.rag.retrieval;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 从结构化课程行提取事实，不使用模型编造的数值或按问题硬编码答案。
 */
public final class CourseEvidence {
    private static final Pattern ROW =
        Pattern.compile(
            "(?m)^\\s*(\\d{6,10})\\s+([^\\r\\n]+?)\\s+(\\d+(?:\\.\\d+)?)\\s+(20\\d{2})\\s*[-—]\\s*(20\\d{2})\\s+([12])(?:\\s|$)");
    private static final Pattern NATURE = Pattern.compile("课程性质[=＝]([^；\\r\\n]+)");
    private static final Pattern GROUP = Pattern.compile("(?m)^分组上下文：([^\\r\\n]+)");
    private static final List<String> YEARS = List.of("大一", "大二", "大三", "大四", "大五", "大六", "大七", "大八");

    private CourseEvidence() {
    }

    public record Fact(
        String code,
        String name,
        BigDecimal credits,
        int startYear,
        int endYear,
        int semester,
        int sourceIndex,
        Integer enrollmentYear,
        String nature) {
        public Fact withSourceIndex(int index) {
            return new Fact(code, name, credits, startYear, endYear, semester, index, enrollmentYear, nature);
        }
    }

    public static List<Fact> extract(List<RetrievedChunk> chunks) {
        List<Fact> facts = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            var rows = ROW.matcher(content);
            while (rows.find()) {
                int start = Integer.parseInt(rows.group(4)), end = Integer.parseInt(rows.group(5));
                if (end != start + 1) {
                    continue;
                }
                // 父上下文包含多门课程，课程性质只能绑定到当前课程行所在区块。
                int blockStart = content.lastIndexOf("[TABLE_ROW ", rows.start());
                int blockEnd = blockStart < 0 ? -1 : content.indexOf("[/TABLE_ROW]", blockStart);
                String nature = null;
                if (blockStart >= 0 && blockEnd >= rows.end()) {
                    String block = content.substring(blockStart, blockEnd);
                    var label = NATURE.matcher(block);
                    if (label.find()) {
                        nature = label.group(1).trim();
                    }
                    // 实践课也可能属于选修分组，不能仅凭“实践”判定为必修。
                    if ("实践".equals(nature)) {
                        var group = GROUP.matcher(block);
                        if (group.find()) {
                            String heading = group.group(1);
                            if (heading.contains("选修") && !heading.contains("必修")) {
                                nature = "选修（实践）";
                            } else if (heading.contains("必修") && !heading.contains("选修")) {
                                nature = "必修（实践）";
                            }
                        }
                    }
                }
                facts.add(
                    new Fact(
                        rows.group(1),
                        rows.group(2).trim(),
                        new BigDecimal(rows.group(3)),
                        start,
                        end,
                        Integer.parseInt(rows.group(6)),
                        i + 1,
                        chunk.getTargetGrade(),
                        nature));
            }
        }
        return facts;
    }

    /**
     * 只消费语义计划的课程实体；缺少任一实体证据时交回常规证据生成，避免输出半份答案。
     */
    public static Optional<String> answer(QueryIntent plan, List<RetrievedChunk> chunks) {
        if (plan.task() != QueryIntent.Task.COURSE_FACT || plan.courseNames().isEmpty()) {
            return Optional.empty();
        }
        List<Fact> facts = extract(chunks);
        List<String> answers = new ArrayList<>();
        for (String course : plan.courseNames()) {
            List<Fact> versions = facts.stream()
                .filter(f -> normalize(f.name()).equals(normalize(course)) || f.code().equals(course))
                .toList();
            if (versions.isEmpty()) {
                return Optional.empty();
            }
            Fact first = versions.getFirst();
            boolean conflict = versions.stream().anyMatch(f -> !f.code().equals(first.code())
                || f.credits().compareTo(first.credits()) != 0
                || f.startYear() != first.startYear() || f.endYear() != first.endYear()
                || f.semester() != first.semester()
                || !Objects.equals(f.enrollmentYear(), first.enrollmentYear()));
            if (conflict) {
                String citations = versions.stream().map(f -> "[" + f.sourceIndex() + "]")
                    .distinct().collect(java.util.stream.Collectors.joining());
                answers.add("《" + first.name() + "》存在不同版本的修读安排" + citations
                    + "，请补充专业和入学年份以确定适用版本。");
            } else {
                answers.add(render(plan.studyYear(), first));
            }
        }
        return Optional.of(String.join("\n", answers));
    }

    private static String render(Integer requestedYear, Fact first) {
        Integer enrollment = first.enrollmentYear();
        int relativeYear = enrollment == null ? 0 : first.startYear() - enrollment + 1;
        String yearName =
            relativeYear >= 1 && relativeYear <= YEARS.size()
                ? YEARS.get(relativeYear - 1)
                : null;
        String prefix = "";
        if (yearName != null && requestedYear != null) {
            String requested = YEARS.get(requestedYear - 1);
            prefix =
                requested.equals(yearName)
                    ? "安排在" + yearName + "。"
                    : "不是" + requested + "，安排在" + yearName + "。";
        }
        return
            prefix
                + "《"
                + first.name()
                + "》（课程号 "
                + first.code()
                + "）为 "
                + first.credits().stripTrailingZeros().toPlainString()
                + " 学分，建议在 "
                + first.startYear()
                + "-"
                + first.endYear()
                + "学年第"
                + first.semester()
                + "学期修读"
                + (yearName == null
                ? ""
                : "（" + yearName + (first.semester() == 1 ? "上学期" : "下学期") + "）")
                + " ["
                + first.sourceIndex()
                + "]。";
    }

    private static String normalize(String value) {
        return value.replaceAll("[\\s《》“”]+", "");
    }
}
