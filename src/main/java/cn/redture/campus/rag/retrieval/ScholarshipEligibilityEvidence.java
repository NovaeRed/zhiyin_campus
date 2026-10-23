package cn.redture.campus.rag.retrieval;

import java.util.*;
import java.util.regex.Pattern;

/** Derives only the grade condition from an explicitly named, retrieved scholarship section. */
public final class ScholarshipEligibilityEvidence {
    private static final Pattern QUESTION_GRADE = Pattern.compile("(?<![零一二三四五六七八九十0-9])([一二三四五六七八九1-9])年级|大([一二三四五六七八九1-9])");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\h*([^\\n\\r：:。]{2,40}(?:奖学金|助学金))\\h*$");
    private static final Pattern TARGET = Pattern.compile("(?:奖励|资助)对象[：:]([^。\\n\\r]+)");
    private static final Pattern MINIMUM = Pattern.compile("([一二三四五六七八九1-9])年级以上[（(]含\\1年级[）)]");
    private static final Pattern ELIGIBILITY_INTENT = Pattern.compile(
            "(?:能否|可否|可不可以|能不能|是否可以|是否能|可以|能)(?:申请|申领)|(?:申请|申领)(?:吗|么)|(?:有|具有|具备)(?:申请|申领)资格");
    private static final Pattern OTHER_INTENT = Pattern.compile(
            "多少|金额|钱|标准|时间|日期|月份|时候|何时|截止|材料|证明|流程|步骤|方式|方法|条件|要求|怎么|如何|什么|哪些|多久|几月|几次");

    private ScholarshipEligibilityEvidence() { }

    public static Optional<String> answer(String question, List<RetrievedChunk> chunks) {
        // A grade mention is context, not permission to replace an amount/process/multi-part question.
        if (question == null || !ELIGIBILITY_INTENT.matcher(question).find()
                || OTHER_INTENT.matcher(question).find()) return Optional.empty();
        var grade = QUESTION_GRADE.matcher(question);
        if (!grade.find()) return Optional.empty();
        int requested = number(grade.group(1) == null ? grade.group(2) : grade.group(1));
        if (grade.find()) return Optional.empty();
        var matches = new ArrayList<Rule>();
        for (int index = 0; index < chunks.size(); index++) {
            String content = chunks.get(index).getContent();
            if (content == null) continue;
            var headings = HEADING.matcher(content);
            var sections = new ArrayList<Section>();
            while (headings.find()) sections.add(new Section(headings.group(1).trim(), headings.start(), headings.end()));
            for (int s = 0; s < sections.size(); s++) {
                var section = sections.get(s);
                if (!question.contains(section.name())) continue;
                String body = content.substring(section.end(), s + 1 < sections.size() ? sections.get(s + 1).start() : content.length());
                var target = TARGET.matcher(body);
                if (!target.find()) continue;
                var minimum = MINIMUM.matcher(target.group(1));
                if (!minimum.find()) continue;
                int value = number(minimum.group(1));
                // Ambiguous or conflicting bounds must be handled by the normal evidence pipeline.
                if (minimum.find() || target.find()) continue;
                matches.add(new Rule(section.name(), value, index + 1));
            }
        }
        if (matches.isEmpty() || matches.stream().map(Rule::name).distinct().count() != 1
                || matches.stream().map(Rule::minimum).distinct().count() != 1) return Optional.empty();
        var rule = matches.getFirst();
        String bound = "一二三四五六七八九".substring(rule.minimum() - 1, rule.minimum());
        if (requested < rule.minimum()) return Optional.of("不可以。根据所提供资料，" + rule.name()
                + "的奖励或资助对象要求为" + bound + "年级以上（含" + bound + "年级），所询问年级不符合这一条件。[" + rule.citation() + "]");
        return Optional.of("就年级条件而言，所询问年级符合所提供资料中" + rule.name() + "的" + bound
                + "年级以上（含" + bound + "年级）要求；能否申请还需核对其他申请条件。[" + rule.citation() + "]");
    }

    private static int number(String value) {
        int chinese = "一二三四五六七八九".indexOf(value);
        return chinese >= 0 ? chinese + 1 : Integer.parseInt(value);
    }
    private record Section(String name, int start, int end) { }
    private record Rule(String name, int minimum, int citation) { }
}
