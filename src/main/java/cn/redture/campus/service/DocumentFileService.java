package cn.redture.campus.service;

import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

/**
 * 文档原文访问服务。
 */
public interface DocumentFileService {

    /**
     * 校验账号和文档权限后，读取受存储目录约束的原文。
     */
    ResponseEntity<Resource> original(long id, boolean download);
}
