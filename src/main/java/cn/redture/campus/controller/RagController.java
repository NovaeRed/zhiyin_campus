package cn.redture.campus.controller;

import cn.redture.campus.pojo.dto.DocumentUploadRequestDTO;
import cn.redture.campus.pojo.dto.RagAskRequestDTO;
import cn.redture.campus.pojo.model.RestResult;
import cn.redture.campus.pojo.vo.DocumentUploadResponseVO;
import cn.redture.campus.pojo.vo.RagAnswerResponseVO;
import cn.redture.campus.rag.service.DocumentIngestionService;
import cn.redture.campus.rag.service.RagQueryOrchestrator;
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

    @PostMapping("/documents")
    public RestResult<DocumentUploadResponseVO> uploadDocument(@RequestPart("file") MultipartFile file,
                                                               @ModelAttribute DocumentUploadRequestDTO request) {
        return RestResult.created(documentIngestionService.ingest(file, request));
    }

    @PostMapping("/ask")
    public RestResult<RagAnswerResponseVO> ask(@RequestBody RagAskRequestDTO request) {
        return RestResult.success(ragQueryOrchestrator.ask(request));
    }
}
