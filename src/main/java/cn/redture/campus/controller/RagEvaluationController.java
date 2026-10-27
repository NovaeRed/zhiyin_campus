package cn.redture.campus.controller;

import cn.redture.campus.pojo.model.RestResult;
import cn.redture.campus.rag.evaluation.EvaluationMarkdown;
import cn.redture.campus.rag.evaluation.EvaluationReport;
import cn.redture.campus.rag.evaluation.EvaluationRequest;
import cn.redture.campus.rag.evaluation.EvaluationService;
import cn.redture.campus.rag.evaluation.RetrievalSnapshot;
import cn.redture.campus.util.SecurityContextHolderUtil;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/rag/evaluations")
public class RagEvaluationController {
    private final EvaluationService service;

    public RagEvaluationController(EvaluationService service) {
        this.service = service;
    }

    @GetMapping("/queries/{queryId}")
    public RestResult<RetrievalSnapshot> snapshot(@PathVariable("queryId") Long queryId) {
        return RestResult.success(service.snapshot(queryId, SecurityContextHolderUtil.getUserId()));
    }

    @PostMapping
    public RestResult<EvaluationReport> evaluate(@Valid @RequestBody EvaluationRequest request) {
        return RestResult.success(service.evaluate(request, SecurityContextHolderUtil.getUserId()));
    }

    @PostMapping(value = "/report", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> report(@Valid @RequestBody EvaluationRequest request) {
        EvaluationReport result = service.evaluate(request, SecurityContextHolderUtil.getUserId());
        return ResponseEntity.ok()
            .header(
                "Content-Disposition",
                "attachment; filename=rag-evaluation-" + result.runId() + ".md")
            .body(EvaluationMarkdown.render(result));
    }
}
