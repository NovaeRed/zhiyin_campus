package cn.redture.campus.service.impl;

import cn.redture.campus.exception.BaseException;
import cn.redture.campus.mapper.WorkflowManagementMapper;
import cn.redture.campus.pojo.entity.User;
import cn.redture.campus.service.AccountAccessService;
import cn.redture.campus.service.OperationAuditService;
import cn.redture.campus.service.PortalService;
import cn.redture.campus.service.WorkflowManagementService;
import cn.redture.campus.util.SecurityContextHolderUtil;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
public class WorkflowManagementServiceImpl implements WorkflowManagementService {
    private final WorkflowManagementMapper workflowManagementMapper;
    private final AccountAccessService accounts;
    private final PortalService portal;
    private final OperationAuditService audit;
    private final ObjectMapper json;

    public WorkflowManagementServiceImpl(
        WorkflowManagementMapper workflowManagementMapper,
        AccountAccessService accounts,
        PortalService portal,
        OperationAuditService audit,
        ObjectMapper json) {
        this.workflowManagementMapper = workflowManagementMapper;
        this.accounts = accounts;
        this.portal = portal;
        this.audit = audit;
        this.json = json;
    }

    private long uid() {
        return accounts.requireActive(SecurityContextHolderUtil.getUserId()).getId();
    }

    @Override
    public User administrator() {
        var user = accounts.requireActive(SecurityContextHolderUtil.getUserId());
        if (!Set.of("admin", "super_admin").contains(user.getRole())) {
            fail(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "仅管理员可执行此操作");
        }
        return user;
    }

    @Override
    public List<Map<String, Object>> users(int page, int size) {
        administrator();
        page(page, size);
        return workflowManagementMapper.selectUsers(size, (page - 1) * size);
    }

    @Transactional
    @Override
    public void updateUser(long id, String role, Boolean enabled) {
        var actor = administrator();
        workflowManagementMapper.lockAdministratorChanges();
        actor = administrator();
        var rows = workflowManagementMapper.lockUser(id);
        if (rows.isEmpty()) {
            missing();
        }
        var target = rows.getFirst();
        if (role == null && enabled == null) {
            invalid();
        }
        if (role != null && !Set.of("student", "editor", "admin", "super_admin").contains(role)) {
            invalid();
        }
        if ((Set.of("admin", "super_admin").contains(target.get("role"))
            || Set.of("admin", "super_admin").contains(role == null ? "" : role))
            && !actor.getRole().equals("super_admin")) {
            fail(HttpStatus.FORBIDDEN, "SUPER_ADMIN_REQUIRED", "管理员权限的调整仅限超级管理员");
        }
        if (actor.getId() == id
            && (Boolean.FALSE.equals(enabled)
            || role != null && !role.equals(actor.getRole()))) {
            fail(HttpStatus.CONFLICT, "SELF_ADMIN_CHANGE", "不可停用或降级自己的管理账号");
        }
        workflowManagementMapper.updateUser(role, enabled, id);
        var detail = new LinkedHashMap<String, Object>();
        detail.put("previous", target);
        detail.put("role", role);
        detail.put("active", enabled);
        audit.record("USER_UPDATE", "user", id, detail);
    }

    @Override
    public Map<String, Object> profile() {
        var rows = workflowManagementMapper.selectProfile(uid());
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    @Transactional
    @Override
    public void profile(String major, Integer grade, String cohort) {
        var user = accounts.requireActive(uid());
        validateScope(major, grade, cohort);
        if (major == null || major.isBlank() || grade == null) {
            invalid();
        }
        workflowManagementMapper.upsertProfile(
            user.getId(), user.getStudentNo(), major.trim(), grade, cohort);
    }

    @Transactional
    @Override
    public void session(
        long id,
        String title,
        Boolean archived,
        String major,
        Integer grade,
        String cohort,
        boolean setScope) {
        long user = uid();
        if (workflowManagementMapper.lockSession(id, user).isEmpty()) {
            missing();
        }
        if (Boolean.TRUE.equals(workflowManagementMapper.hasRunningRequest(id))) {
            fail(HttpStatus.CONFLICT, "SESSION_BUSY", "请在当前问答完成后修改会话");
        }
        if (title != null && (title.isBlank() || title.length() > 120)) {
            invalid();
        }
        workflowManagementMapper.updateSession(title, archived, id, user);
        if (setScope) {
            validateScope(major, grade, cohort);
            var scope = new LinkedHashMap<String, Object>();
            if (major != null && !major.isBlank()) {
                scope.put("major", major.trim());
            }
            if (grade != null) {
                scope.put("grade", grade);
            }
            if (cohort != null) {
                scope.put("cohort", cohort);
            }
            workflowManagementMapper.updateScope(write(scope), id);
        }
    }

    @Transactional
    @Override
    public int deleteSessions(List<Long> ids) {
        long user = uid();
        if (ids == null
            || ids.isEmpty()
            || ids.size() > 100
            || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            invalid();
        }
        var unique = new TreeSet<>(ids);
        // 与请求声明和会话更新使用相同行锁，防止检查空闲后、删除前插入新问题。
        var sessions = workflowManagementMapper.lockSessions(user, unique);
        if (sessions.size() != unique.size()) {
            missing();
        }
        if (Boolean.TRUE.equals(workflowManagementMapper.hasRunningRequests(unique))) {
            fail(HttpStatus.CONFLICT, "SESSION_BUSY", "选中的对话仍在回答中，请等待完成或取消后再删除");
        }
        int deleted = workflowManagementMapper.deleteSessions(user, unique);
        if (deleted > 0) {
            audit.record(
                "SESSION_DELETE",
                "session",
                unique.first(),
                Map.of("ids", unique, "count", deleted));
        }
        return deleted;
    }

    @Override
    public List<Map<String, Object>> archived(int page, int size) {
        page(page, size);
        return workflowManagementMapper.selectArchived(uid(), size, (page - 1) * size);
    }

    @Override
    public Map<String, Object> scope(long id) {
        var rows = workflowManagementMapper.selectScope(id, uid());
        if (rows.isEmpty()) {
            missing();
        }
        return rows.getFirst();
    }

    @Transactional
    @Override
    public void feedback(long query, int rating, String comment) {
        long user = uid();
        if ((rating != 1 && rating != -1) || comment == null || comment.length() > 2000) {
            invalid();
        }
        if (!Boolean.TRUE.equals(workflowManagementMapper.ownsCompletedQuery(query, user))) {
            missing();
        }
        workflowManagementMapper.upsertFeedback(query, user, rating, comment);
    }

    @Override
    public List<Map<String, Object>> audits(int page, int size) {
        administrator();
        page(page, size);
        return workflowManagementMapper.selectAudits(size, (page - 1) * size);
    }

    @Transactional
    @Override
    public void editDocument(long id, String title, String version) {
        editDocument(
            id, new cn.redture.campus.pojo.dto.DocumentEditRequestDTO(title, version, null));
    }

    @Transactional
    @Override
    public void editDocument(long id, cn.redture.campus.pojo.dto.DocumentEditRequestDTO request) {
        String title = request.title(), version = request.version();
        portal.requireManager();
        if (title == null
            || title.isBlank()
            || title.length() > 255
            || version == null
            || version.isBlank()
            || version.length() > 32) {
            invalid();
        }
        var locked = workflowManagementMapper.lockDocument(id);
        if (locked.isEmpty()) {
            missing();
        }
        var before = portal.document(id);
        if (workflowManagementMapper.updateDocument(title.trim(), version.trim(), id) != 1) {
            fail(HttpStatus.CONFLICT, "DOCUMENT_BUSY", "仅可编辑已完成入库的文档；正文或切片变更请执行版本重建");
        }
        var detail = new LinkedHashMap<String, Object>();
        detail.put("previousTitle", before.getTitle());
        detail.put("title", title.trim());
        detail.put("previousVersion", before.getVersion());
        detail.put("version", version.trim());
        if (request.metadata() != null) {
            var meta = request.metadata();
            String major = blankToNull(meta.targetMajor()),
                cohort = blankToNull(meta.targetCohort()),
                department = blankToNull(meta.department());
            validateScope(major, meta.targetGrade(), cohort);
            if (department != null && department.length() > 100) {
                invalid();
            }
            var patch = new LinkedHashMap<String, Object>();
            patch.put("target_major", major);
            patch.put("target_grade", meta.targetGrade());
            patch.put("target_cohort", cohort);
            patch.put("department", department);
            patch.put(
                "effective_date",
                meta.effectiveDate() == null ? null : meta.effectiveDate().toString());
            // 全局忽略空值的序列化配置不能抹去显式清空检索范围的字段。
            var patchNode = json.createObjectNode();
            patch.forEach(
                (key, value) ->
                    patchNode.set(
                        key,
                        value == null
                            ? com.fasterxml.jackson.databind.node.NullNode.instance
                            : json.valueToTree(value)));
            String encoded = write(patchNode);
            workflowManagementMapper.updateDocumentMetadata(encoded, id);
            workflowManagementMapper.updateChunkMetadata(major, meta.targetGrade(), encoded, id);
            detail.put("previousMetadata", locked.getFirst().get("metadata"));
            detail.put("metadata", patchNode);
        }
        audit.record("DOCUMENT_EDIT", "document", id, detail);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateScope(String major, Integer grade, String cohort) {
        if (major != null && major.length() > 100
            || grade != null && (grade < 1900 || grade > 2200)
            || cohort != null && !cohort.matches("20\\d{2}届")) {
            invalid();
        }
    }

    private void page(int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) {
            invalid();
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void missing() {
        fail(HttpStatus.NOT_FOUND, "NOT_FOUND", "记录不存在或无权访问");
    }

    private void invalid() {
        fail(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请求参数不合法");
    }

    private void fail(HttpStatus status, String code, String message) {
        throw new BaseException(status, message, code);
    }
}
