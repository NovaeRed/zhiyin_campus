package cn.redture.campus.rag.retrieval;

import java.util.Locale;
import java.util.regex.Pattern;

/** Sorting signals cannot substitute for affirmative evidence about an explicitly named subject. */
public final class EvidencePolicy {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_.+#-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALIAS = Pattern.compile("([\\p{IsHan}]{4,16})[（(]?([a-z][a-z0-9_.+#-]+)[）)]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern OBJECT = Pattern.compile("^([\\p{IsHan}a-zA-Z0-9]{3,30}(?:系统|平台|项目|应用))(?=的|中|里|[？?，,]|$)");
    private static final Pattern PROGRAM = Pattern.compile("^((?:国家|省级|校级)[\\p{IsHan}]{0,12}(?:奖学金|助学金|补助|津贴))");

    private EvidencePolicy() { }

    public static boolean supports(String question, RetrievedChunk chunk, double minimumCoverage) {
        if (question.matches("(?s).*(培养方案|执行计划).*") && chunk.getTargetGrade() == null
                && AcademicScope.inferEnrollmentYear(chunk.getContent()) == null) return false;
        String document = text(chunk.getTitle()) + " " + text(chunk.getContent());
        var identifiers = IDENTIFIER.matcher(question);
        while (identifiers.find()) {
            String identifier = identifiers.group().toLowerCase(Locale.ROOT);
            if (document.contains(identifier)) continue;
            var aliases = ALIAS.matcher(question);
            boolean supportedAlias = false;
            while (aliases.find()) {
                if (aliases.group(2).equalsIgnoreCase(identifier) && document.contains(aliases.group(1))) supportedAlias = true;
            }
            if (!supportedAlias) return false;
        }
        String subjectQuestion = question.replaceFirst("^(?:请问|请介绍|这个|这|该)", "");
        var object = OBJECT.matcher(subjectQuestion);
        if (object.find()) {
            String identity = object.group(1).replaceFirst("(?:系统|平台|项目|应用)$", "");
            if (identity.length() >= 3 && !document.contains(identity.toLowerCase(Locale.ROOT))) return false;
        }
        var program = PROGRAM.matcher(subjectQuestion);
        if (program.find() && !document.contains(program.group(1))) return false;
        return chunk.getEvidenceScore() != null && Double.isFinite(chunk.getEvidenceScore())
                && chunk.getEvidenceScore() >= minimumCoverage;
    }

    private static String text(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
}
