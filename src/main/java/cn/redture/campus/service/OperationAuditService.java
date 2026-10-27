package cn.redture.campus.service;

/**
 * 操作审计服务。
 */
public interface OperationAuditService {

    /**
     * 将当前操作者和变更详情写入审计记录，参与调用方已有事务。
     */
    void record(String action, String type, Object id, Object detail);
}
