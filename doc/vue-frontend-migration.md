# 前端 Vue 迁移（2026-10-24）

分支：`codex/vue-frontend-migration`，基于 `bugfix/admin-documents-usability` 的 `cb10972`，包含已验证的原文预览、属性编辑和文档删除能力。不合并或推送主分支。

## 实现范围

- 入口改为 `src/main.ts` / `App.vue`，使用Vue 3 Composition API和`<script setup lang="ts">`；Vue Router替代React Router，页面按路由异步加载。
- Ant Design Vue替代React版Ant Design，构建插件、vue-tsc、ESLint和依赖锁文件同步迁移。当前锁定Vue3.5.44、Vue Router4.6.4、Ant Design Vue4.2.6、Axios1.20.0、Vite8.3.5；兼容范围内更新依赖后npm audit为0。
- 登录和注册、认证状态及退出、学生管理页权限提示、404、校园问答、来源展开、历史恢复/单条和批量删除全部迁移。
- 聊天保留请求幂等键、响应丢失后同键重放、未知终态保留键、失败回填草稿、输入范围、输入法Enter保护、Shift+Enter、多页历史和浏览器前进/后退。切换会话或卸载时失效旧异步结果。
- 文档上传/校验、状态启停、原文PDF/HTML/TXT/MD预览与下载、属性保存/清空/取消、切片分页及删除确认迁移；保留HTML沙箱隔离和对象URL释放。
- 现有CSS、校园图片、同源`/api`代理和Axios服务契约保留。未修改后端Java、数据库结构或正式知识文档；本轮不重启后端。

原React页面/组件/入口移动至 `frontend/legacy-react/`，保留实际工作区版本以供对照，没有批量删除文件。归档不参与构建、类型检查、lint或运行，依赖中已移除React运行库、类型和插件。没有将归档声称为另一套可直接运行的应用。

实现参照 [Vue TypeScript Composition API](https://vuejs.org/guide/typescript/composition-api)、[Vue Router](https://router.vuejs.org/guide/) 和 [Ant Design Vue](https://antdv.com/)。

## 验证结果

本地前端15175已重启为Vue（PID36764），继续代理现有18082后端。没有停止用户其他后端端口。以下均使用真实HTTP、真实配置模型或真实浏览器，没有Mock回答；响应丢失测试只拦截已真实完成的响应传输。

| 检查 | 通过数 | 原始记录 |
| --- | --- | --- |
| 聊天幂等、丢失响应、会话切换、浏览器前进后退 | 11 | `target/vue-ui-reliability/result.json` |
| 原文下载/预览、属性保存/取消/清空、刷新 | 11 | `target/admin-documents-browser/checks.json` |
| 历史接口权限、真实流取消及历史批量删除/分页/手机布局 | 37 | `target/history-delete-live/result.json` |
| 注册校验、登录/退出、权限、引用、离线草稿、上传和375/768/1440布局 | 27 | `target/vue-general-ui/result.json` |
| 启用/停用文档的删除确认、取消、刷新 | 7 | `target/document-delete-browser/checks.json` |
| 用户原始计算机/数学口语课程问题与来源 | 4 | `target/colloquial-browser/result.json` |
| Vue实际挂载、注册成功、IME、年份校验、94门长回答手机滚动和404 | 8 | `target/vue-migration-browser/checks.json` |
| 合计 | 105 | 所有最终记录均无失败 |

迁移中实测发现并修复两处同源问题：新对话先显示欢迎页但URL仍在旧会话；以及新对话后立即输入的草稿被稍后路由回调清空。现在内部新对话跳转只重置一次，路由完成前保留加载状态，旧历史回调还校验会话代次。上述最终综合和可靠性测试均通过。旧综合测试切片定位改为先选“切片正文”标签，符合已存在的原文默认页行为；测试兼容调整留在本地，不进入迁移提交。

实际浏览器上传过专用文档64，后续布局回归复用它以避免重复Embedding，最后停用；其他删除验收仅删除自建测试文档。测试新增账号、会话、问答及文档记录并调用模型，不能称为业务库只读。综合测试对专用验收账号临时赋予文档权限后恢复student；用户管理员密码和正式知识文档未修改。没有执行完整RAG效果题库，不将本轮界面迁移验收等同于全部问答质量重新达标。

`npm run build`和`npm run lint`通过；`npm ls react react-dom antd --depth=0`为空。生产构建保留Ant Design Vue主包体积提示，已拆分页面路由，尚未做组件按需导入。新增验收脚本和测试产物保留本地，不进入分支提交。
