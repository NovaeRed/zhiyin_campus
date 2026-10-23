package cn.redture.campus.controller;

import cn.redture.campus.pojo.model.RestResult;
import cn.redture.campus.rag.service.ReliableRagService;
import cn.redture.campus.service.WorkflowManagementService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class WorkflowController {
    private final WorkflowManagementService management; private final ReliableRagService rag;
    public WorkflowController(WorkflowManagementService management,ReliableRagService rag){this.management=management;this.rag=rag;}
    public record Profile(String major,Integer grade,String cohort){}
    public record SessionEdit(String title,Boolean archived,String targetMajor,Integer targetGrade,String targetCohort,boolean setScope){}
    public record UserEdit(String role,Boolean active){}
    public record Feedback(int rating,String comment){}
    public record SessionDelete(List<Long> ids){}
    @GetMapping("/account/profile") public RestResult<?> profile(){return RestResult.success(management.profile());}
    @PutMapping("/account/profile") public RestResult<?> profile(@RequestBody Profile body){management.profile(body.major(),body.grade(),body.cohort());return RestResult.success();}
    @PutMapping("/rag/sessions/{id}") public RestResult<?> session(@PathVariable("id") long id,@RequestBody SessionEdit body){management.session(id,body.title(),body.archived(),body.targetMajor(),body.targetGrade(),body.targetCohort(),body.setScope());return RestResult.success();}
    @DeleteMapping("/rag/sessions/{id}") public RestResult<?> deleteSession(@PathVariable("id") long id){return RestResult.success(Map.of("deletedCount",management.deleteSessions(List.of(id))));}
    @PostMapping("/rag/sessions/batch-delete") public RestResult<?> deleteSessions(@RequestBody SessionDelete body){return RestResult.success(Map.of("deletedCount",management.deleteSessions(body.ids())));}
    @GetMapping("/rag/sessions/{id}") public RestResult<?> session(@PathVariable("id") long id){return RestResult.success(management.scope(id));}
    @GetMapping("/rag/sessions/archived") public RestResult<?> archived(@RequestParam(name="page",defaultValue="1") int page,@RequestParam(name="pageSize",defaultValue="20") int pageSize){return RestResult.success(management.archived(page,pageSize));}
    @GetMapping("/rag/requests/{key}") public RestResult<?> request(@PathVariable("key") String key){return RestResult.success(rag.status(key));}
    @PostMapping("/rag/requests/{key}/cancel") public RestResult<?> cancel(@PathVariable("key") String key){rag.cancel(key);return RestResult.success();}
    @PutMapping("/rag/queries/{id}/feedback") public RestResult<?> feedback(@PathVariable("id") long id,@RequestBody Feedback body){management.feedback(id,body.rating(),body.comment()==null?"":body.comment());return RestResult.success();}
    @GetMapping("/admin/users") public RestResult<?> users(@RequestParam(name="page",defaultValue="1") int page,@RequestParam(name="pageSize",defaultValue="20") int pageSize){return RestResult.success(management.users(page,pageSize));}
    @PutMapping("/admin/users/{id}") public RestResult<?> user(@PathVariable("id") long id,@RequestBody UserEdit body){management.updateUser(id,body.role(),body.active());return RestResult.success();}
    @GetMapping("/admin/audit-events") public RestResult<?> audit(@RequestParam(name="page",defaultValue="1") int page,@RequestParam(name="pageSize",defaultValue="20") int pageSize){return RestResult.success(management.audits(page,pageSize));}
    @PutMapping("/admin/documents/{id}") public RestResult<?> document(@PathVariable("id") long id,@jakarta.validation.Valid @RequestBody cn.redture.campus.pojo.dto.DocumentEditRequestDTO body){management.editDocument(id,body);return RestResult.success();}
}
