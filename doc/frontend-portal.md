# 前后端功能落地与管理员文档管理

2026-10-23。本轮基于现有 users、knowledge_docs、doc_chunks、rag_sessions、rag_queries、retrieval_traces、generation_traces 实现页面和缺少的接口；无需新增表，也没有执行初始化SQL。数据库中仅预留的反馈、路由缓存、调优等表仍不代表已有服务和页面。

## 访问与权限

前端 http://localhost:5173；后台路径 `/admin/documents`。首次使用先注册和登录。注册账号固定为student，不能在表单里选择管理员角色。

文档维护权限允许 admin、super_admin、editor。管理接口和旧上传入口都在服务端从数据库读取当前账号的角色与启用状态进行验证；无需依赖客户端保存的角色。撤销角色后，现有JWT也不能继续管理文档。`/account/me`不返回密码哈希或文件存储路径。

系统负责人可使用数据库管理工具对一个已核实的账号配置角色，示例需要先将占位学号换成真实值再手动执行：

```sql
UPDATE users SET role = 'admin', updated_at = NOW()
WHERE student_no = '实际学号' AND role = 'student'
  AND is_active = true AND deleted_at IS NULL
RETURNING id, student_no, username, role;
```

本轮未将任何既有学生账号永久提升为管理员，也未创建默认管理员密码。权限修改后刷新页面重新获取账号信息。

## 页面与功能

- 问答：使用已有POST /rag/ask；传入question、sessionId及可选targetMajor、targetGrade、targetCohort，保留入学年和毕业届别的区别。展示回答、来源标题和可展开的原文。
- 历史：分页列出当前用户的会话，打开后恢复历史问题、答案及当时的检索来源快照。只展示完成问答的答案，未完成记录有明确提示。拒答和引用校验失败不重新发布被抑制的来源。会话ID只用于归组，不为现有LLM增加多轮记忆。
- 文档：按标题搜索、按状态筛选、分页浏览。详情显示元数据、上传人、父子切片数量、完整切片和父ID。
- 添加：独立管理抽屉包含文件、标题、版本、recursive/parent_child策略、专业、入学年、毕业届别、部门、生效日期。自动策略由后端决定。校验格式、20MB限制和基本字段长度；后端重复校验元数据约束。
- 启停：仅active/deprecated可相互转换，failed/processing不能被手动标成active。数据库事务内锁定文档并同步所有切片状态。保留原文件与向量，不删除资料，不重新Embedding。
- 认证：保存access/refresh token，处理HTTP和RestResult业务错误，自动刷新并重试一次，失败回到登录；注销清除本地令牌。

## 接口契约

响应保持RestResult格式，业务对象在data。旧注册和上传HTTP状态为200、业务码201；旧注销HTTP状态为200、业务码204。业务异常现在同时设置对应HTTP错误状态。前端兼容业务错误码和HTTP错误。

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| GET `/account/me` | 已登录且账号有效 | id、studentNo、username、role、canManageDocuments |
| POST `/rag/documents` | 文档维护角色 | 现有multipart上传接口，增加角色及DTO校验 |
| GET `/admin/documents` | 文档维护角色 | search/status/page/pageSize；默认10条，最多100条 |
| GET `/admin/documents/{id}` | 文档维护角色 | 文档元数据与切片数量，不暴露file_path |
| GET `/admin/documents/{id}/chunks` | 文档维护角色 | 分页切片正文、级别和父关联 |
| PUT `/admin/documents/{id}/status` | 文档维护角色 | JSON `{ "status": "active" }` 或deprecated |
| GET `/rag/sessions` | 当前用户 | 会话标题取首次问题，分页 |
| GET `/rag/sessions/{id}/queries` | 会话所属用户 | 问题、完成状态、答案和当时的来源快照，分页 |

问答的sessionId现在按user_id验证后才能续写，其他用户的ID返回404，并在查询规划/模型调用前结束。列表与明细SQL使用MyBatis绑定参数，分页限制在服务层校验。CORS支持当前localhost:5173的认证预检。

## 实际验证

1. Maven生产构建成功，跳过单元测试；前端生产构建和ESLint成功。
2. `PortalHttpIT`（显式portal.live.enabled=true）对最新8080真实服务完成32项检查，包括未登录401、学生管理/上传403、他人会话读取与续写404、非法分页400、上传元数据、父子切片、启停事务、撤销角色立即生效、刷新及注销。课程问题使用真实规划/Embedding和当前资料，检查来源版本及合法引用；这不是完整事实质量标准集。
3. Edge/Playwright真实浏览器完成登录、学生导航、问答与历史恢复、刷新恢复、直访管理页403、真实文件上传、标题搜索、切片展开、停用，以及375/768/1440宽度检查。最终15项通过且无未捕获浏览器异常。首次真实浏览器上传成功后，后续重跑复用文档31，不重复Embedding。截图见 `target/frontend-ui/`。
4. 本轮新增专用账号和问答轨迹；测试维护员仅临时提权，结束后恢复student并注销。文档30（HTTP上传）、31（浏览器上传）最终deprecated，切片也全部deprecated。原有文档26～29未被本轮测试修改。不能称整个业务库零写入。
5. 已有Start-SecureBackend脚本读取本机DPAPI配置后启动最新JAR，运行PID38456。旧ParentChildHttpIT大题库因缺少 `doc/evaluation/compact-audit-v1.json` 未执行问答，不能宣称旧31题全部通过。

可复现命令：先用已有 `scripts/Start-SecureBackend.ps1` 在本机加载运行环境并启动后端，然后执行：

```powershell
mvn test-compile failsafe:integration-test failsafe:verify -B -ntp "-Dit.test=PortalHttpIT" "-Dportal.live.enabled=true"
```

浏览器验收还需运行Vite、Microsoft Edge，并将PORTAL_TEST_PG_DRIVER指向本机Maven缓存中的PostgreSQL JDBC JAR，然后在frontend执行 `npm run test:ui:live`。该测试会创建独立测试数据并调用真实模型；不应在未明确验收时自动运行。

结果：`target/portal-http/checks.json`、`target/frontend-ui/result.json`、`target/failsafe-reports/cn.redture.campus.rag.evaluation.PortalHttpIT.txt`。默认测试不启动收费HTTP验收。当前未实现文档编辑重建、任意PDF的OCR、用户角色管理页面、学生档案编辑或评测后台；没有据预留表创建虚构的功能入口。
