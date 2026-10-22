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

    public static Result answer(String question, int enrollment, List<RetrievedChunk> rows) {
        var year = YEAR.matcher(question);
        if (!year.find()) throw new IllegalArgumentException("Missing academic year");
        int start = enrollment + "一二三四五".indexOf(year.group(1));
        var selected = new LinkedHashMap<String, RetrievedChunk>();
        for (var row : rows) {
            if (CourseEvidence.extract(List.of(row)).stream().anyMatch(f -> f.startYear() == start)) {
                selected.putIfAbsent(row.getDocId() + ":" + (row.getParentId() == null ? "c" + row.getChunkId() : "p" + row.getParentId()), row);
            }
        }
        var sources = List.copyOf(selected.values());
        if (sources.isEmpty()) return new Result("根据现有资料无法回答。", List.of());
        var facts = new LinkedHashMap<String, CourseEvidence.Fact>();
        CourseEvidence.extract(sources).stream().filter(f -> f.startYear() == start)
                .sorted(Comparator.comparingInt(CourseEvidence.Fact::semester).thenComparing(CourseEvidence.Fact::code))
                .forEach(f -> facts.putIfAbsent(f.code() + ":" + f.semester(), f));
        StringBuilder answer = new StringBuilder(enrollment + "级培养方案中，大" + year.group(1) + "（" + start + "-" + (start + 1)
                + "学年）建议修读课程如下。包含必修和选修安排，不代表全部必须选修，课程性质以原文为准。\n");
        for (var fact : facts.values()) answer.append("- 第").append(fact.semester()).append("学期：《").append(fact.name())
                .append("》（").append(fact.code()).append("），").append(fact.credits().stripTrailingZeros().toPlainString())
                .append(" 学分 [").append(fact.sourceIndex()).append("]\n");
        return new Result(answer.toString().trim(), sources);
    }
}
