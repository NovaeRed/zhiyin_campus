package cn.redture.campus.rag.retrieval;

import java.util.regex.Pattern;

/**
 * 从文档标题中提取专业和入学年份。
 */
public record CurriculumMetadata(String major, Integer enrollmentYear) {
    private static final Pattern HEADER = Pattern.compile(
        "(?m)^\\h*(20\\d{2})(?:级|年|版)?\\h*([^\\r\\n]{2,80}?)(?:专业)?(?:培养方案|执行计划)\\h*$");

    /**
     * 仅从文档前部的培养方案或执行计划标题格式读取专业和入学年。
     *
     * @param text 文档标题或已提取正文，可为空
     * @return 提取到的元数据，未识别字段为 {@code null}
     */
    public static CurriculumMetadata extract(String text) {
        var header = HEADER.matcher(text == null ? "" : text.substring(0, Math.min(500, text.length())));
        return header.find()
            ? new CurriculumMetadata(header.group(2).trim(), Integer.valueOf(header.group(1)))
            : new CurriculumMetadata(null, null);
    }

    /**
     * 优先读取文档已有专业字段，缺失时回退到文档标题格式解析。
     *
     * @param document 有效目录中的培养方案文档
     * @return 规范化专业名称，现有字段和标题均无法确认时为 {@code null}
     */
    public static String majorOf(CurriculumDocument document) {
        return document.getTargetMajor() != null && !document.getTargetMajor().isBlank()
            ? document.getTargetMajor().trim()
            : extract(document.getTitle()).major();
    }
}
