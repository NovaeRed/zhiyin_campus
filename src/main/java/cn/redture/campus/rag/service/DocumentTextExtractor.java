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
import java.util.Set;

@Component
public class DocumentTextExtractor {

    private static final Set<String> PLAIN_TEXT_EXTENSIONS = Set.of("txt", "md", "html");

    public String extract(Path path, String originalFilename, String contentType, String fileType) {
        try {
            if (PLAIN_TEXT_EXTENSIONS.contains(fileType)) {
                return Files.readString(path, StandardCharsets.UTF_8);
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
            return handler.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Document parse failed: " + e.getMessage(), e);
        }
    }
}
