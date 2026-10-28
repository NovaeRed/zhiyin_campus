package cn.redture.campus.rag.service;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

@Component
public class DocumentTextExtractor {

    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt", "md");

    public String extract(Path path, String originalFilename, String contentType, String fileType) {
        return extractDetailed(path, originalFilename, contentType, fileType).text();
    }

    public record Extraction(String text, Map<String, Object> metadata) {
    }

    /**
     * 按文件类型读取正文；PDF使用坐标解析并返回复核信息，文本按UTF-8读取，其他支持格式交给Tika提取正文。
     *
     * @param path             已保存的本地原文路径
     * @param originalFilename 用于格式识别的原始文件名
     * @param contentType      上传时的媒体类型，可为空
     * @param fileType         已校验的小写文件扩展名
     * @return 完整提取正文及提取器、课程行校验和人工复核元数据
     */
    public Extraction extractDetailed(
        Path path, String originalFilename, String contentType, String fileType) {
        try {
            if (PLAIN_TEXT_EXTENSIONS.contains(fileType)) {
                return new Extraction(
                    Files.readString(path, StandardCharsets.UTF_8),
                    Map.of("extractor", "plain-text"));
            }
            if ("pdf".equals(fileType)) {
                var result = PdfLayoutTextExtractor.extract(path);
                return new Extraction(
                    result.text(),
                    Map.of(
                        "extractor",
                        "pdf-layout-v1",
                        "pages",
                        result.pages(),
                        "detected_course_rows",
                        result.detectedRows(),
                        "validated_course_rows",
                        result.acceptedRows(),
                        "review_required",
                        !result.issues().isEmpty(),
                        "issues",
                        result.issues(),
                        "replacement_characters",
                        result.text().chars().filter(c -> c == 0xFFFD).count()));
            }

            AutoDetectParser parser = new AutoDetectParser();
            ContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            if (StringUtils.hasText(originalFilename)) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalFilename);
            }
            if (StringUtils.hasText(contentType)) {
                metadata.set(Metadata.CONTENT_TYPE, contentType);
            }
            try (InputStream inputStream = Files.newInputStream(path)) {
                parser.parse(inputStream, handler, metadata, new ParseContext());
            }
            return new Extraction(handler.toString(), Map.of("extractor", "tika"));
        } catch (Exception e) {
            throw new IllegalArgumentException("Document parse failed: " + e.getMessage(), e);
        }
    }
}
