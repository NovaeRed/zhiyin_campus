package cn.redture.campus.rag.retrieval;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * 完整课程清单不能依赖相似度检索的前 K 条样本生成。
 */
public final class CourseSchedule {
    private CourseSchedule() {
    }

    public record Result(String answer, List<RetrievedChunk> sources) {
    }

    /**
     * 学年、学期和课程性质已由语义计划提供；完整清单只解析一次课程行。
     */
    public static Result answer(
        int enrollment,
        Integer studyYear,
        Integer semester,
        String nature,
        boolean countCourses,
        List<RetrievedChunk> rows) {
        Integer start = studyYear == null ? null : enrollment + studyYear - 1;
        var scoped =
            CourseEvidence.extract(rows).stream()
                .filter(f -> matches(f, start, semester))
                .toList();
        long unspecified =
            scoped.stream()
                .filter(f -> !knownNature(f))
                .map(CourseEvidence.Fact::code)
                .distinct()
                .count();
        var sources = new ArrayList<RetrievedChunk>();
        var sourcePositions = new HashMap<String, Integer>();
        var citations = new HashMap<Integer, Integer>();
        for (var fact : scoped) {
            if (!matchesNature(fact, nature) && knownNature(fact)) {
                continue;
            }
            var row = rows.get(fact.sourceIndex() - 1);
            String key = row.getDocId() + ":"
                + (row.getParentId() == null ? "c" + row.getChunkId() : "p" + row.getParentId());
            Integer position = sourcePositions.get(key);
            if (position == null) {
                sources.add(row);
                position = sources.size();
                sourcePositions.put(key, position);
            }
            citations.put(fact.sourceIndex(), position);
        }
        if (sources.isEmpty()) {
            return new Result(
                scoped.isEmpty()
                    ? "根据现有资料无法回答。"
                    : "该范围内未找到原文明确标注为"
                    + ("REQUIRED".equals(nature) ? "必修" : "选修")
                    + "的课程安排。",
                List.of());
        }
        var facts = new LinkedHashMap<String, CourseEvidence.Fact>();
        scoped.stream()
            .filter(f -> matchesNature(f, nature))
            .sorted(
                Comparator.comparingInt(CourseEvidence.Fact::startYear)
                    .thenComparingInt(CourseEvidence.Fact::semester)
                    .thenComparing(CourseEvidence.Fact::code))
            .forEach(
                f ->
                    facts.putIfAbsent(
                        f.code() + ":" + f.startYear() + ":" + f.semester(),
                        f.withSourceIndex(citations.get(f.sourceIndex()))));
        String period =
            start == null
                ? "全部学年"
                : "大"
                + "一二三四五六七八".charAt(studyYear - 1)
                + "（"
                + start
                + "-"
                + (start + 1)
                + "学年）";
        if (semester != null) {
            period += semester == 1 ? "上学期（第1学期）" : "下学期（第2学期）";
        }
        String category =
            "REQUIRED".equals(nature) ? "必修课" : "ELECTIVE".equals(nature) ? "选修课" : "课程";
        long count = facts.values().stream().map(CourseEvidence.Fact::code).distinct().count();
        StringBuilder answer =
            new StringBuilder(
                enrollment
                    + "级培养方案中，"
                    + period
                    + "共 "
                    + count
                    + " 门"
                    + (!"ALL".equals(nature) && unspecified > 0 ? "原文明示的" : "")
                    + category);
        if (count != facts.size()) {
            answer.append("（").append(facts.size()).append(" 条修读安排，同课程号按一门计）");
        }
        answer.append("，清单如下。");
        if ("ALL".equals(nature)) {
            answer.append("包含必修和选修安排，不代表全部必须选修，课程性质以原文为准。");
        } else {
            answer.append("仅统计原文明确标注为")
                .append("REQUIRED".equals(nature) ? "必修" : "选修")
                .append("的课程。");
        }
        if (countCourses) {
            answer.append("这里按课程门数统计；培养方案的修读安排不能确定实际上课节数，也不代表你个人尚未修完的课程数。");
        }
        answer.append('\n');
        for (var fact : facts.values()) {
            answer.append("- ")
                .append(fact.startYear())
                .append("-")
                .append(fact.endYear())
                .append("学年第")
                .append(fact.semester())
                .append("学期：《")
                .append(fact.name())
                .append("》（")
                .append(fact.code())
                .append("），")
                .append(fact.credits().stripTrailingZeros().toPlainString())
                .append(" 学分 [")
                .append(fact.sourceIndex())
                .append("]\n");
        }
        if (!"ALL".equals(nature) && unspecified > 0) {
            String unknownCitations =
                scoped.stream()
                    .filter(f -> !knownNature(f))
                    .map(f -> "[" + citations.get(f.sourceIndex()) + "]")
                    .distinct()
                    .collect(java.util.stream.Collectors.joining());
            answer.append("另有 ")
                .append(unspecified)
                .append(" 门课程原文仅标注实践等性质，未明确必修或选修，未计入上述门数；不能据此断言全部必修义务或任选范围。")
                .append(unknownCitations);
        }
        return new Result(answer.toString().trim(), List.copyOf(sources));
    }

    private static boolean matches(CourseEvidence.Fact fact, Integer start, Integer semester) {
        return (start == null || fact.startYear() == start)
            && (semester == null || fact.semester() == semester);
    }

    private static boolean knownNature(CourseEvidence.Fact fact) {
        return fact.nature() != null
            && (fact.nature().contains("必修") ^ fact.nature().contains("选修"));
    }

    private static boolean matchesNature(CourseEvidence.Fact fact, String nature) {
        return "ALL".equals(nature)
            || (knownNature(fact)
            && fact.nature().contains("REQUIRED".equals(nature) ? "必修" : "选修"));
    }
}
