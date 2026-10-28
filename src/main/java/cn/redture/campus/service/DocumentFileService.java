package cn.redture.campus.service;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

/**
 * 文档原文访问服务。
 */
public interface DocumentFileService {

    /**
     * 校验账号和文档可见性后读取受存储目录约束的原文，以预览或下载方式返回。
     *
     * @param id       原文所属文档标识
     * @param download 为 {@code true} 时使用附件下载响应，否则按可支持类型预览
     * @return 包含原文资源、媒体类型及安全文件名的HTTP响应
     */
    ResponseEntity<Resource> original(long id, boolean download);
}
