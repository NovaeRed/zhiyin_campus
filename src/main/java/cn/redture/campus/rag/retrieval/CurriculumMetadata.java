package cn.redture.campus.rag.retrieval;

import java.util.regex.Pattern;

/**
 * 仅解析文档标题格式，不读取用户问题或决定检索意图。
 */
public record CurriculumMetadata(String major, Integer enrollmentYear) {
    private static final Pattern HEADER = Pattern.compile(
        "(?m)^\\h*(20\\d{2})(?:级|年|版)?\\h*([^\\r\\n]{2,80}?)(?:专业)?(?:培养方案|执行计划)\\h*$");

    public static CurriculumMetadata extract(String text) {
        var header = HEADER.matcher(text == null ? "" : text.substring(0, Math.min(500, text.length())));
        return header.find()
            ? new CurriculumMetadata(header.group(2).trim(), Integer.valueOf(header.group(1)))
            : new CurriculumMetadata(null, null);
    }

    public static String majorOf(CurriculumDocument document) {
        return document.getTargetMajor() != null && !document.getTargetMajor().isBlank()
            ? document.getTargetMajor().trim()
            : extract(document.getTitle()).major();
    }
}
