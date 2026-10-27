package cn.redture.campus.service;

import cn.redture.campus.pojo.entity.User;

import java.util.List;
import java.util.Map;

/**
 * 业务工作流管理服务。
 */
public interface WorkflowManagementService {

    User administrator();

    List<Map<String, Object>> users(int page, int size);

    void updateUser(long id, String role, Boolean enabled);

    Map<String, Object> profile();

    void profile(String major, Integer grade, String cohort);

    void session(
        long id,
        String title,
        Boolean archived,
        String major,
        Integer grade,
        String cohort,
        boolean setScope);

    int deleteSessions(List<Long> ids);

    List<Map<String, Object>> archived(int page, int size);

    Map<String, Object> scope(long id);

    void feedback(long query, int rating, String comment);

    List<Map<String, Object>> audits(int page, int size);

    void editDocument(long id, String title, String version);

    void editDocument(long id, cn.redture.campus.pojo.dto.DocumentEditRequestDTO request);
}
