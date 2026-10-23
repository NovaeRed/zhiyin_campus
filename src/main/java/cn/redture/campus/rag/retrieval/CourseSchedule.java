package cn.redture.campus.rag.retrieval;

import java.util.*;
import java.util.regex.Pattern;

/** Exhaustive schedule queries cannot be answered reliably by a similarity Top-K sample. */
public final class CourseSchedule {
    private static final Pattern YEAR = Pattern.compile("大([一二三四五])");
    private CourseSchedule() { }

    public static boolean requested(String question) {
        return YEAR.matcher(question).find() && question.matches("(?s).*(什么课|哪些课|课程清单|课程列表|要修的课|的课程有哪些).*");
    }

    public record Result(String answer, List<RetrievedChunk> sources) { }

    public static Integer studyYear(String question) {
        var year = YEAR.matcher(question);
        return year.find() ? "一二三四五".indexOf(year.group(1)) + 1 : null;
    }

    public static Integer semester(String question) {
        boolean first = question.contains("上学期") || question.contains("第一学期") || question.contains("第1学期") || question.contains("秋季学期");
        boolean second = question.contains("下学期") || question.contains("第二学期") || question.contains("第2学期") || question.contains("春季学期");
        return first == second ? null : first ? 1 : 2;
    }

    public static Result answer(String question, int enrollment, List<RetrievedChunk> rows) {
        return answer(enrollment, studyYear(question), semester(question), rows);
    }

    /** Null studyYear means the entire plan, not a Top-K sample or the current calendar year. */
    public static Result answer(int enrollment, Integer studyYear, Integer semester, List<RetrievedChunk> rows) {
        return answer(enrollment, studyYear, semester, "ALL", false, rows);
    }

    public static Result answer(int enrollment, Integer studyYear, Integer semester, String nature, boolean countCourses, List<RetrievedChunk> rows) {
        Integer start = studyYear == null ? null : enrollment + studyYear - 1;
        var scoped = CourseEvidence.extract(rows).stream().filter(f -> matches(f, start, semester)).toList();
        long unspecified = scoped.stream().filter(f -> !knownNature(f)).map(CourseEvidence.Fact::code).distinct().count();
        var selected = new LinkedHashMap<String, RetrievedChunk>();
        for (var row : rows) {
            if (CourseEvidence.extract(List.of(row)).stream().anyMatch(f -> matches(f, start, semester)
                    && (matchesNature(f, nature) || (!"ALL".equals(nature) && !knownNature(f))))) {
                selected.putIfAbsent(row.getDocId() + ":" + (row.getParentId() == null ? "c" + row.getChunkId() : "p" + row.getParentId()), row);
            }
        }
        var sources = List.copyOf(selected.values());
        if (sources.isEmpty()) return new Result(scoped.isEmpty() ? "根据现有资料无法回答。"
                : "该范围内未找到原文明确标注为" + ("REQUIRED".equals(nature) ? "必修" : "选修") + "的课程安排。", List.of());
        var facts = new LinkedHashMap<String, CourseEvidence.Fact>();
        CourseEvidence.extract(sources).stream().filter(f -> matches(f, start, semester) && matchesNature(f, nature))
                .sorted(Comparator.comparingInt(CourseEvidence.Fact::startYear).thenComparingInt(CourseEvidence.Fact::semester).thenComparing(CourseEvidence.Fact::code))
                .forEach(f -> facts.putIfAbsent(f.code() + ":" + f.startYear() + ":" + f.semester(), f));
        String period = start == null ? "全部学年" : "大" + "一二三四五六七八".charAt(studyYear - 1) + "（" + start + "-" + (start + 1) + "学年）";
        if (semester != null) period += semester == 1 ? "上学期（第1学期）" : "下学期（第2学期）";
        String category = "REQUIRED".equals(nature) ? "必修课" : "ELECTIVE".equals(nature) ? "选修课" : "课程";
        long count = facts.values().stream().map(CourseEvidence.Fact::code).distinct().count();
        StringBuilder answer = new StringBuilder(enrollment + "级培养方案中，" + period + "共 " + count + " 门"
                + (!"ALL".equals(nature) && unspecified > 0 ? "原文明示的" : "") + category);
        if (count != facts.size()) answer.append("（").append(facts.size()).append(" 条修读安排，同课程号按一门计）");
        answer.append("，清单如下。");
        if ("ALL".equals(nature)) answer.append("包含必修和选修安排，不代表全部必须选修，课程性质以原文为准。");
        else answer.append("仅统计原文明确标注为").append("REQUIRED".equals(nature) ? "必修" : "选修").append("的课程。");
        if (countCourses) answer.append("这里按课程门数统计；培养方案的修读安排不能确定实际上课节数，也不代表你个人尚未修完的课程数。");
        answer.append('\n');
        for (var fact : facts.values()) answer.append("- ").append(fact.startYear()).append("-").append(fact.endYear()).append("学年第").append(fact.semester()).append("学期：《").append(fact.name())
                .append("》（").append(fact.code()).append("），").append(fact.credits().stripTrailingZeros().toPlainString())
                .append(" 学分 [").append(fact.sourceIndex()).append("]\n");
        if (!"ALL".equals(nature) && unspecified > 0) {
            String citations = CourseEvidence.extract(sources).stream().filter(f -> matches(f, start, semester) && !knownNature(f))
                    .map(f -> "[" + f.sourceIndex() + "]").distinct().collect(java.util.stream.Collectors.joining());
            answer.append("另有 ").append(unspecified).append(" 门课程原文仅标注实践等性质，未明确必修或选修，未计入上述门数；不能据此断言全部必修义务或任选范围。").append(citations);
        }
        return new Result(answer.toString().trim(), sources);
    }

    private static boolean matches(CourseEvidence.Fact fact, Integer start, Integer semester) {
        return (start == null || fact.startYear() == start) && (semester == null || fact.semester() == semester);
    }

    private static boolean knownNature(CourseEvidence.Fact fact) {
        return fact.nature() != null && (fact.nature().contains("必修") ^ fact.nature().contains("选修"));
    }

    private static boolean matchesNature(CourseEvidence.Fact fact, String nature) {
        return "ALL".equals(nature) || (knownNature(fact) && fact.nature().contains("REQUIRED".equals(nature) ? "必修" : "选修"));
    }
}
