package cn.redture.campus.service;

import cn.redture.campus.exception.BaseException;
import cn.redture.campus.mapper.KnowledgeMapper;
import cn.redture.campus.mapper.PortalMapper;
import cn.redture.campus.mapper.UserMapper;
import cn.redture.campus.pojo.entity.User;
import cn.redture.campus.pojo.vo.PortalViews.*;
import cn.redture.campus.util.SecurityContextHolderUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

@Service
public class PortalService {
    private static final Set<String> MANAGERS = Set.of("admin", "super_admin", "editor");
    private static final Set<String> STATUSES = Set.of("pending", "processing", "active", "deprecated", "failed");
    private final UserMapper users;
    private final PortalMapper portal;
    private final KnowledgeMapper knowledge;
    private final AccountAccessService accounts;
    private final OperationAuditService audit;
    public PortalService(UserMapper users, PortalMapper portal, KnowledgeMapper knowledge, AccountAccessService accounts, OperationAuditService audit) {
        this.users = users; this.portal = portal; this.knowledge = knowledge; this.accounts=accounts; this.audit=audit;
    }
    public User currentUser() {
        return accounts.requireActive(SecurityContextHolderUtil.getUserId());
    }
    public Account account() {
        User user = currentUser();
        return new Account(user.getId(), user.getStudentNo(), user.getUsername(), user.getRole(), MANAGERS.contains(user.getRole()));
    }
    public void requireManager() {
        if (!MANAGERS.contains(currentUser().getRole()))
            throw new BaseException(HttpStatus.FORBIDDEN, "仅管理员或知识编辑人员可管理文档", "DOCUMENT_MANAGEMENT_FORBIDDEN");
    }
    public Page<Document> documents(String search, String status, int page, int size) {
        requireManager(); validatePage(page, size);
        if (search.length() > 255 || (!status.isEmpty() && !STATUSES.contains(status))) badInput();
        return new Page<>(portal.documents(search.trim(), status, size, (page - 1) * size), portal.countDocuments(search.trim(), status), page, size);
    }
    public Document document(Long id) {
        requireManager(); Document doc = portal.document(id);
        if (doc == null) missing(); return doc;
    }
    public Page<Chunk> chunks(Long id, int page, int size) {
        document(id); validatePage(page, size);
        return new Page<>(portal.chunks(id, size, (page - 1) * size), portal.countChunks(id), page, size);
    }
    @Transactional
    public void changeStatus(Long id, String status) {
        requireManager();
        if (!Set.of("active", "deprecated").contains(status == null ? "" : status)) badInput();
        String current = portal.lockStatus(id);
        if (current == null) missing();
        if (!Set.of("active", "deprecated").contains(current))
            throw new BaseException(HttpStatus.CONFLICT, "仅可启用或停用已完成入库的文档；失败文档请重新上传", "INVALID_DOCUMENT_TRANSITION");
        knowledge.updateDocumentStatus(id, status); knowledge.updateChunksStatus(id, status);
        audit.record("DOCUMENT_STATUS","document",id,java.util.Map.of("previous",current,"status",status));
    }
    public Page<Session> sessions(int page, int size) {
        Long userId = currentUser().getId(); validatePage(page, size);
        return new Page<>(portal.sessions(userId, size, (page - 1) * size), portal.countSessions(userId), page, size);
    }
    public Page<HistoryQuery> history(Long id, int page, int size) {
        Long userId = currentUser().getId(); validatePage(page, size);
        if (!portal.ownsSession(id, userId)) missing();
        return new Page<>(portal.history(id, userId, size, (page - 1) * size), portal.countHistory(id, userId), page, size);
    }
    private void validatePage(int page, int size) { if (page < 1 || page > 100000 || size < 1 || size > 100) badInput(); }
    private void badInput() { throw new BaseException(HttpStatus.BAD_REQUEST, "请求参数不合法", "INVALID_INPUT"); }
    private void missing() { throw new BaseException(HttpStatus.NOT_FOUND, "记录不存在或无权访问", "NOT_FOUND"); }
}
