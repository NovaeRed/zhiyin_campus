# 8081前端真实自动化与迭代

日期：2026-10-23。使用用户已启动的localhost:8081后端（PID40392），未修改或重启后端；使用真实Microsoft Edge，无HTTP Mock、无拦截伪造响应。

## 修复与环境核对

- 原5173端口属于另一个项目，因此保持其进程。本项目Vite固定5174且strictPort=true，避免误打开其他网站。
- 开发API默认走同源/api，由Vite代理到8081，目标可用VITE_BACKEND_TARGET配置。开发代理去除浏览器Origin头，前端无需为开发端口改动后端CORS。生产静态部署需单独配置VITE_API_BASE_URL或实际反向代理。
- 浏览器实际定位到旧Vite进程的Outdated Optimize Dep/504导致白屏；仅核实并重启本项目前端，未删除缓存目录或其他文件。
- 真实断网发送复现问题：失败请求仍在消息区留下一条用户问题，重试形成重复。修复为移除本次失败条目、恢复草稿；若用户已输入新草稿则不覆盖，并通过同步ref避免同一帧重复提交。
- 浏览器测试URL、后端和输出目录参数化；测试角色辅助程序从现有application-local.yaml读数据库配置，环境变量仍可覆盖。不加载DPAPI、不运行撤销的凭证轮换/安全启动脚本，不输出任何凭证。

## 验证结果

`npm run test:ui:live`：27项通过，failure=null，无未捕获浏览器异常。

覆盖注册密码强度及二次密码、真实登录、学生导航、学生直访后台403、缺失资料拒答、会话恢复与刷新、真实课程清单及来源原文展开、Shift+Enter不提交、真实断网恢复不重复、文件格式/空文件/标题校验、文档搜索、切片预览、停用、注销清除两类令牌及回到登录，以及375/768/1440下的问答/管理/上传布局和375/768下的登录注册布局。

测试用浏览器网络请求核对前端均通过同源代理。当前代理配置明确指向8081，未对8080发出验收请求。课程查询用于验证页面与来源展示，不等同于完整RAG事实质量题集验收。

`npm run build`和`npm run lint`均通过。构建仍有主JS包大于500kB提示，本轮未调整依赖版本或拆包。

## 数据与进程影响

测试注册专用portal_ui_时间戳学生账号，临时提升同一专用账号以测试管理页面，最终恢复student并注销。测试会新增账号、会话、问答轨迹。

本轮不上传新文档，复用既有浏览器验收文档31，只对它执行启用/停用验证，最终文档及切片恢复deprecated；没有重新Embedding。页面上传校验已覆盖，但本轮未重新执行文件成功入库链路。原业务文档没有被此次脚本修改。原8080、8081及其他项目进程均未停止。

## 复现

先由用户启动8081后端，在frontend运行npm run dev。测试需要本机Microsoft Edge，以及私有application-local.yaml。PowerShell示例（路径按本机Maven缓存调整）：

```powershell
$env:PORTAL_TEST_JAVA_CLASSPATH = 'C:/Users/thexi/.m2/repository/org/postgresql/postgresql/42.7.8/postgresql-42.7.8.jar;C:/Users/thexi/.m2/repository/org/yaml/snakeyaml/2.4/snakeyaml-2.4.jar'
$env:PORTAL_UI_REUSE_DOC = '31'
npm run test:ui:live
```

测试在frontend目录执行。PORTAL_UI_REUSE_DOC仅适用于已核实的测试文档，不应替换成业务文档。省略该变量会执行真实文件上传并产生Embedding费用和新的停用测试文档。

结果：`target/frontend-ui-8081/result.json`；截图位于同一目录，包括login-desktop.png、documents-desktop.png、upload-desktop.png、document-detail.png、chat-375.png、documents-375.png、upload-375.png、login-375.png、register-375.png及对应768尺寸截图。failure.png属于修复前失败快照，按禁止批量删除要求保留，不代表最终测试失败。

## 历史对话动态数据核验

2026-10-23针对“历史是否写死”的专项检查：生产代码无需替换，未发现静态历史数组、Mock响应或失败时伪造对话的回退数据。History.tsx从GET /rag/sessions读列表，Chat.tsx从GET /rag/sessions/{id}/queries恢复正文；PortalMapper按当前认证user_id查询rag_sessions/rag_queries/generation_traces/retrieval_traces。会话标题取第一条问题的前80字符，相同开场问题可能产生相同标题。

新增 `frontend/scripts/verify-history.mjs --live`，对8081/5174真实运行10项检查全部通过。两个新学生账号验证：初始列表为空；页面打开后新增唯一问题，刷新出现实际sessionId和标题；打开并刷新后问题/答案与真实问答响应及queryId一致；另一账号列表仍空，直读该会话返回404，前端显示错误而不展示样例消息。结果在 `target/history-live/result.json`，本次会话ID214，截图为同目录real-history-list.png。

本次未修改生产实现或重启后端；只创建专项测试账号、会话与轨迹并注销，未变更知识文档或账号权限。该唯一问题使用不存在的2099培养方案验证数据库持久化，不是RAG模型效果评测。
