package cn.redture.campus.service.impl;

import cn.redture.campus.config.RagProperties;
import cn.redture.campus.exception.BaseException;
import cn.redture.campus.mapper.DocumentFileMapper;
import cn.redture.campus.service.DocumentFileService;
import cn.redture.campus.service.PortalService;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Service
public class DocumentFileServiceImpl implements DocumentFileService {
    private final PortalService portal;
    private final DocumentFileMapper documentFileMapper;
    private final RagProperties properties;
    private final ObjectMapper json;

    public DocumentFileServiceImpl(
        PortalService portal,
        DocumentFileMapper documentFileMapper,
        RagProperties properties,
        ObjectMapper json) {
        this.portal = portal;
        this.documentFileMapper = documentFileMapper;
        this.properties = properties;
        this.json = json;
    }

    /**
     * 校验文档权限及真实路径的存储目录归属，并清理响应文件名后返回原文。
     *
     * @param id       待访问原文的文档标识
     * @param download 是否以附件下载方式响应
     * @return 包含可读原文资源及媒体类型的HTTP响应
     */
    @Override
    public ResponseEntity<Resource> original(
        long id, boolean download) {
        var doc = portal.document(id); // 每次读取都校验当前账号状态和文档访问权限。
        String stored = documentFileMapper.selectFilePath(id);
        try {
            Path root =
                Path.of(properties.getStorage().getLocalFileDir())
                    .toAbsolutePath()
                    .normalize()
                    .toRealPath();
            Path file = Path.of(stored).toAbsolutePath().normalize();
            if (!file.startsWith(root)) {
                throw unavailable();
            }
            // 规范路径仍可能包含符号链接；解析真实路径后再检查一次目录归属。
            file = file.toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw unavailable();
            }
            String type = doc.getFileType().toLowerCase(java.util.Locale.ROOT);
            String mime =
                Map.of(
                        "pdf",
                        "application/pdf",
                        "html",
                        "text/html;charset=UTF-8",
                        "txt",
                        "text/plain;charset=UTF-8",
                        "md",
                        "text/plain;charset=UTF-8",
                        "docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .get(type);
            if (mime == null) {
                throw unavailable();
            }
            String name = json.readTree(doc.getMetadata()).path("file_name").asText("");
            if (name.isBlank()) {
                name = doc.getTitle() + "." + type;
            }
            name = name.replaceAll("[\\r\\n\\\\/]", "_");
            var disposition =
                (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                    .filename(name, StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime))
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(
                    "Content-Security-Policy",
                    "sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:")
                .body(new FileSystemResource(file));
        } catch (java.io.IOException | IllegalArgumentException | NullPointerException missing) {
            throw unavailable();
        }
    }

    private BaseException unavailable() {
        return new BaseException(
            HttpStatus.NOT_FOUND, "原始文件不存在或暂时无法读取，请重新上传原文。", "DOCUMENT_FILE_UNAVAILABLE");
    }
}
