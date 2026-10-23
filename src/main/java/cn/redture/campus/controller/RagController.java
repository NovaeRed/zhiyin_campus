package cn.redture.campus.controller;

import cn.redture.campus.pojo.dto.DocumentUploadRequestDTO;
import cn.redture.campus.pojo.dto.RagAskRequestDTO;
import cn.redture.campus.pojo.model.RestResult;
import cn.redture.campus.pojo.vo.DocumentUploadResponseVO;
import cn.redture.campus.pojo.vo.RagAnswerResponseVO;
import cn.redture.campus.rag.service.DocumentIngestionService;
import cn.redture.campus.rag.service.RagQueryOrchestrator;
import cn.redture.campus.service.PortalService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/rag")
public class RagController {

    @Resource
    private DocumentIngestionService documentIngestionService;

    @Resource
    private RagQueryOrchestrator ragQueryOrchestrator;
    @Resource
    private cn.redture.campus.rag.service.ReliableRagService reliableRagService;
    @Resource
    private cn.redture.campus.rag.service.IngestionJobService ingestionJobs;

    @Resource
    private PortalService portalService;

    @PostMapping("/documents")
    public RestResult<DocumentUploadResponseVO> uploadDocument(@RequestPart("file") MultipartFile file,
                                                               @jakarta.validation.Valid @ModelAttribute DocumentUploadRequestDTO request) {
        portalService.requireManager();
        return RestResult.created(documentIngestionService.ingest(file, request));
    }

    @PostMapping("/ask")
    public RestResult<RagAnswerResponseVO> ask(@RequestBody RagAskRequestDTO request,
            @org.springframework.web.bind.annotation.RequestHeader(name="Idempotency-Key",required=false) String key) {
        if(key!=null) {
            if(request.getRequestId()!=null&&!key.equals(request.getRequestId()))throw new IllegalArgumentException("Idempotency-Key与requestId不一致");
            request.setRequestId(key);
        }
        return RestResult.success(reliableRagService.ask(request));
    }

    @PostMapping("/document-jobs")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public RestResult<?> enqueue(@RequestPart("file") MultipartFile file,@jakarta.validation.Valid @ModelAttribute DocumentUploadRequestDTO request) {
        return RestResult.accepted(ingestionJobs.submit(file,request,null));
    }
    @org.springframework.web.bind.annotation.GetMapping("/document-jobs/{id}")
    public RestResult<?> job(@org.springframework.web.bind.annotation.PathVariable("id") java.util.UUID id){return RestResult.success(ingestionJobs.status(id));}
    @PostMapping("/document-jobs/{id}/retry")
    public RestResult<?> retry(@org.springframework.web.bind.annotation.PathVariable("id") java.util.UUID id,@org.springframework.web.bind.annotation.RequestParam(name="acknowledgeModelCost",defaultValue="false") boolean acknowledged){ingestionJobs.retry(id,acknowledged);return RestResult.success();}
    @PostMapping("/document-jobs/{id}/cancel")
    public RestResult<?> cancel(@org.springframework.web.bind.annotation.PathVariable("id") java.util.UUID id){ingestionJobs.cancel(id);return RestResult.success();}
    @PostMapping("/documents/{id}/rebuild")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public RestResult<?> rebuild(@org.springframework.web.bind.annotation.PathVariable("id") Long id,@RequestPart("file") MultipartFile file,@jakarta.validation.Valid @ModelAttribute DocumentUploadRequestDTO request){return RestResult.accepted(ingestionJobs.submit(file,request,id));}
}
