package cn.redture.campus.service;

import cn.redture.campus.config.RagProperties;
import cn.redture.campus.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

@Service
public class DocumentFileService {
    private final PortalService portal;
    private final JdbcTemplate db;
    private final RagProperties properties;
    private final ObjectMapper json;
    public DocumentFileService(PortalService portal,JdbcTemplate db,RagProperties properties,ObjectMapper json) {
        this.portal=portal;this.db=db;this.properties=properties;this.json=json;
    }
    public ResponseEntity<org.springframework.core.io.Resource> original(long id,boolean download) {
        var doc=portal.document(id); // Check the current account and document permission on every read.
        String stored=db.queryForObject("SELECT file_path FROM knowledge_docs WHERE id=?",String.class,id);
        try {
            Path root=Path.of(properties.getStorage().getLocalFileDir()).toAbsolutePath().normalize().toRealPath();
            Path file=Path.of(stored).toAbsolutePath().normalize();
            if(!file.startsWith(root))throw unavailable();
            file=file.toRealPath();
            if(!file.startsWith(root)||!Files.isRegularFile(file)||!Files.isReadable(file))throw unavailable();
            String type=doc.getFileType().toLowerCase(java.util.Locale.ROOT);
            String mime=Map.of("pdf","application/pdf","html","text/html;charset=UTF-8","txt","text/plain;charset=UTF-8",
                    "md","text/plain;charset=UTF-8","docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").get(type);
            if(mime==null)throw unavailable();
            String name=json.readTree(doc.getMetadata()).path("file_name").asText("");
            if(name.isBlank())name=doc.getTitle()+"."+type;
            name=name.replaceAll("[\\r\\n\\\\/]","_");
            var disposition=(download?ContentDisposition.attachment():ContentDisposition.inline()).filename(name,StandardCharsets.UTF_8).build();
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime)).contentLength(Files.size(file))
                    .header(HttpHeaders.CONTENT_DISPOSITION,disposition.toString()).header(HttpHeaders.CACHE_CONTROL,"no-store")
                    .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'; style-src 'unsafe-inline'; img-src data:")
                    .body(new FileSystemResource(file));
        } catch(java.io.IOException|IllegalArgumentException|NullPointerException missing) {throw unavailable();}
    }
    private BaseException unavailable(){return new BaseException(HttpStatus.NOT_FOUND,"原始文件不存在或暂时无法读取，请重新上传原文。","DOCUMENT_FILE_UNAVAILABLE");}
}
