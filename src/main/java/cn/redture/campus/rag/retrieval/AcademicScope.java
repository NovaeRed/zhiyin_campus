package cn.redture.campus.rag.retrieval;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Enrollment year (级) and graduation cohort (届) are independent dimensions. */
public record AcademicScope(Integer grade, String cohort) {
    private static final Pattern EXPLICIT = Pattern.compile("(?<!\\d)(20\\d{2}|\\d{2})\\s*([级届])");
    private static final Pattern LEADING_CURRICULUM_YEAR = Pattern.compile("^(?:请问\\s*)?(20\\d{2})(?=[\\p{IsHan}])");
    private static final Pattern CURRICULUM_HEADER = Pattern.compile(
            "(?m)^\\s*(20\\d{2})[^\\r\\n]{0,80}(?:执行计划|培养方案)");

    public static AcademicScope resolve(String question, Integer grade, String cohort) {
        var matcher = EXPLICIT.matcher(question);
        Set<Integer> grades = new LinkedHashSet<>();
        Set<String> cohorts = new LinkedHashSet<>();
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            if (year < 100) year += 2000;
            if ("级".equals(matcher.group(2))) grades.add(year);
            else cohorts.add(year + "届");
        }
        // A leading year attached to a curriculum subject is not a publication date or graduation cohort.
        String normalized = question.trim();
        if (grades.isEmpty() && cohorts.isEmpty() && normalized.matches("(?s).*(培养|执行计划|学分|学期|课程|修读).*")) {
            var leading = LEADING_CURRICULUM_YEAR.matcher(normalized);
            if (leading.find() && !normalized.substring(leading.end()).matches("(?s)^(年|届|级|版|毕业).*")) {
                grades.add(Integer.valueOf(leading.group(1)));
            }
        }
        if (grades.size() > 1 || cohorts.size() > 1) {
            throw new IllegalArgumentException("Multiple academic versions require separate retrieval requests");
        }
        return new AcademicScope(grades.isEmpty() ? grade : grades.iterator().next(),
                cohorts.isEmpty() ? cohort : cohorts.iterator().next());
    }

    public static Integer inferEnrollmentYear(String text) {
        if (text == null) return null;
        var matcher = CURRICULUM_HEADER.matcher(text.substring(0, Math.min(500, text.length())));
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
