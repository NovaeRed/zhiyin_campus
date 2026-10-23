package cn.redture.campus.controller;

import cn.redture.campus.pojo.model.RestResult;
import cn.redture.campus.pojo.vo.PortalViews.*;
import cn.redture.campus.service.PortalService;
import org.springframework.web.bind.annotation.*;

@RestController
public class PortalController {
    private final PortalService service;
    public PortalController(PortalService service) { this.service = service; }
    @GetMapping("/account/me")
    public RestResult<Account> me() { return RestResult.success(service.account()); }
    @GetMapping("/admin/documents")
    public RestResult<Page<Document>> documents(@RequestParam(name="search", defaultValue="") String search, @RequestParam(name="status", defaultValue="") String status,
            @RequestParam(name="page", defaultValue="1") int page, @RequestParam(name="pageSize", defaultValue="10") int pageSize) {
        return RestResult.success(service.documents(search, status, page, pageSize));
    }
    @GetMapping("/admin/documents/{id}")
    public RestResult<Document> document(@PathVariable("id") Long id) { return RestResult.success(service.document(id)); }
    @GetMapping("/admin/documents/{id}/chunks")
    public RestResult<Page<Chunk>> chunks(@PathVariable("id") Long id, @RequestParam(name="page", defaultValue="1") int page,
            @RequestParam(name="pageSize", defaultValue="10") int pageSize) { return RestResult.success(service.chunks(id, page, pageSize)); }
    public record StatusRequest(String status) {}
    @PutMapping("/admin/documents/{id}/status")
    public RestResult<Void> status(@PathVariable("id") Long id, @RequestBody StatusRequest request) {
        service.changeStatus(id, request.status()); return RestResult.success();
    }
    @GetMapping("/rag/sessions")
    public RestResult<Page<Session>> sessions(@RequestParam(name="page", defaultValue="1") int page, @RequestParam(name="pageSize", defaultValue="20") int pageSize) {
        return RestResult.success(service.sessions(page, pageSize));
    }
    @GetMapping("/rag/sessions/{id}/queries")
    public RestResult<Page<HistoryQuery>> history(@PathVariable("id") Long id, @RequestParam(name="page", defaultValue="1") int page,
            @RequestParam(name="pageSize", defaultValue="50") int pageSize) { return RestResult.success(service.history(id, page, pageSize)); }
}
