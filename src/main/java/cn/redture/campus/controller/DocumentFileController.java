package cn.redture.campus.controller;

import cn.redture.campus.service.DocumentFileService;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class DocumentFileController {
    private final DocumentFileService files;
    public DocumentFileController(DocumentFileService files){this.files=files;}
    @GetMapping("/admin/documents/{id}/original")
    public ResponseEntity<Resource> original(@PathVariable("id") long id,@RequestParam(name="download",defaultValue="false") boolean download) {
        return files.original(id,download);
    }
}
