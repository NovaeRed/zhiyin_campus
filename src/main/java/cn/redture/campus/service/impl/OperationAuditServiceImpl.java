package cn.redture.campus.service.impl;

import cn.redture.campus.mapper.OperationAuditMapper;
import cn.redture.campus.service.OperationAuditService;
import cn.redture.campus.util.SecurityContextHolderUtil;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

@Service
public class OperationAuditServiceImpl implements OperationAuditService {
    private final OperationAuditMapper operationAuditMapper;
    private final ObjectMapper json;

    public OperationAuditServiceImpl(OperationAuditMapper operationAuditMapper, ObjectMapper json) {
        this.operationAuditMapper = operationAuditMapper;
        this.json = json;
    }

    @Override
    public void record(String action, String type, Object id, Object detail) {
        try {
            operationAuditMapper.insertEvent(
                SecurityContextHolderUtil.getUserId(),
                action,
                type,
                String.valueOf(id),
                json.writeValueAsString(detail));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
