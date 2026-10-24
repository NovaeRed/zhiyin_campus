# 知引校园前端

当前前端使用 Vue 3、TypeScript、Vue Router、Vite 和 Ant Design Vue。页面采用 `<script setup>` 单文件组件，登录/注册、聊天、历史和文档管理按路由加载，沿用暖白、松绿和校园书院图片，手机、平板和桌面共用一套页面。

Vue迁移分支为 `codex/vue-frontend-migration`，入口 `src/main.ts`、路由 `src/router/index.ts`，共享认证状态位于 `src/composables/auth.ts`。旧React源码保留在 `legacy-react/` 供对照，未物理删除，不参与构建、类型检查、ESLint或运行；当前依赖不再包含React、React DOM、React Router或React版Ant Design。构建使用 `vue-tsc -b && vite build`。下方较早的验证记录属于历史React版本，当前迁移验收见 [Vue迁移报告](../doc/vue-frontend-migration.md)。

聊天页占满当前视口，导航与输入区保持可见，欢迎内容、历史消息和长回答在独立区域滚动；多行输入不会把整页向下撑开。2026-10-23使用真实数学全学程94条课程回答，在1440×900、1366×600、768×900、375×667、390×844检查滚动与输入区位置，并验证新对话及历史列表，共7项通过；截图与记录位于 `target/chat-viewport/`。前端build/lint通过，本轮只修改布局，未修改后端。

运行：`npm ci`、`npm run dev`，访问 http://localhost:5174。开发请求默认走同源 `/api` 代理到 http://localhost:8081，可通过 `VITE_BACKEND_TARGET` 覆盖目标；无需修改后端CORS或重启后端。端口冲突时Vite明确报错，不自动跳转到另一个项目。生产请求默认也使用当前站点 `/api`，部署时应将 `/api/` 反向代理到后端并移除 `/api` 前缀；也可在构建时通过 `VITE_API_BASE_URL` 指定API地址并配置后端CORS。Vite开发代理不随静态构建部署。构建 `npm run build`，规范检查 `npm run lint`。

| 路由 | 功能 |
| --- | --- |
| `/login`、`/register` | 学号登录、学生注册、密码校验 |
| `/` | 提问、专业/入学年/毕业届别过滤、原文来源展开 |
| `/history` | 当前用户历史会话、重新打开、刷新恢复、单条删除及勾选批量删除 |
| `/admin/documents` | 管理员/编辑的文档列表、筛选、添加、切片详情、启停 |

本次前端提交不包含工作区中尚未提交的后端扩展。运行前需部署配套接口，尤其是 `/account/me`、`/rag/sessions` 和 `/admin/documents`；接口清单见下方前后端功能说明。既有后端若缺少这些接口，页面无法完成账号验证、历史加载与文档管理。

注册默认 student，文档维护角色为 admin、super_admin、editor。权限由后端读取 users.role 判断；页面导航也按 `/account/me` 返回的能力显示。管理员配置、接口契约和验收记录见 [前后端功能说明](../doc/frontend-portal.md)。没有内置默认管理员密码或公开提权入口。

共享页面已移除重复的“页面名称／基于校园知识库”标题栏，历史页只保留导航中的“开启新对话”。历史卡片的删除按钮和复选框独立于继续对话链接；“全选本页”只选择当前页，支持取消选择、删除确认、失败保留列表，以及删空末页后回到有效页。删除是后端持久化逻辑删除，刷新和旧链接不能恢复，后台审计轨迹仍保留。真实回归显式运行 `node frontend/scripts/verify-history-delete.mjs --live`，使用独立测试账号，不删除已有用户的对话。

上传表单支持文件、标题、版本、切片策略、专业、入学年、毕业届别、部门、生效日期。同步请求等待后端完成解析、Embedding 和入库，以最终响应显示成功或失败，不显示虚构进度。支持 PDF/DOCX/XLSX/MD/HTML/TXT，20MB；扫描件需先OCR。停用保留资料和历史来源，并同步切片状态，使文档退出后续检索。

令牌刷新采用单次并发刷新后重试，刷新失败需重新登录。回答按纯文本渲染，不执行HTML。首个回答完成后将sessionId保存到URL，刷新可恢复后端历史记录。查询携带requestId，丢失响应后保持页面并重试相同问题和范围时复用该键，避免重复执行；修改问题/范围或开启新对话会创建新请求。该重试键只保存在当前页面内存中，刷新前尚未确认的请求不承诺跨页面恢复。后端支持有界会话指代及范围优先级，详见[后端工作流](../doc/backend-workflows.md)；前端查询范围输入仍可显式覆盖后端会话和画像默认值。

新增回归脚本 `node scripts/verify-reliability.mjs --live` 默认连接5175/8082，可通过 `PORTAL_UI_BASE_URL`、`PORTAL_UI_BACKEND_URL` 和 `PORTAL_UI_OUTPUT` 覆盖。脚本真实完成后端问答后仅在浏览器丢弃其响应，检查同键重放的queryId、数据库历史数量、首问URL刷新恢复和新会话重置；不使用Mock回答，不上传或重建知识文档，会新增测试账号/会话/轨迹。

图片 `public/campus-garden.png` 使用内置 image_gen 生成，接入问答和认证页。提示词：

> Use case: stylized-concept. Asset type: campus knowledge assistant web banner and login illustration. Create a sophisticated editorial 3D miniature university campus, ivory modern library with arched windows, sage green trees, warm terracotta walkway, small open book sculpture, peaceful academic atmosphere. Wide landscape 3:2 composition, campus concentrated on right two thirds, soft cream background and empty space on left, delicate paper and matte clay textures, soft daylight, forest green and ivory palette with subtle apricot accents. No text, no logos, no watermark, no UI.

2026-10-23：生产构建和ESLint通过；32项真实HTTP流程检查通过；15项Edge浏览器检查通过，并保存375/768/1440尺寸截图。真实文件上传另外已在浏览器完成，后续布局复测复用该文档。没有使用Mock。检查使用专用账号，产生真实模型费用和两份最终停用的验收文档。结果见项目 `target/portal-http/`、`target/frontend-ui/`。旧标准题库文件缺失，旧大题库验收未完成，本次流程检查不替代全面RAG质量评测。生产JS包仍有体积提示，可后续按路由拆分。

浏览器验收显式执行 `npm run test:ui:live`，需要运行最新后端和Vite、已安装Microsoft Edge，以及项目根目录现有私有 `application-local.yaml` 和 `PORTAL_TEST_JAVA_CLASSPATH`（PostgreSQL JDBC与SnakeYAML JAR组成的classpath）。配置仅由Java测试辅助程序在内存中读取，不加载轮换脚本、不打印凭证。数据库环境变量可覆盖本地配置。只对专用 `portal_ui_时间戳` 账号临时赋予管理员角色，最终恢复student；上传验收文档最终停用。测试脚本不会输出登录令牌、密码和配置凭证。


2026-10-23 后续8081前端迭代：真实Edge自动化扩展为27项并全部通过。覆盖注册密码校验、登录/注销、权限隔离、课程引用、历史恢复、Shift+Enter、断网草稿、文档表单校验/搜索/详情/停用，以及375/768/1440布局。发现并修复失败发送残留重复消息；失败回填不会覆盖用户新输入的草稿，并用同步标记阻止重复发送。当前未重新上传文档或重新Embedding，复用旧验收文档31并最终停用，临时测试维护员恢复student。测试截图与结果位于 `target/frontend-ui-8081/`，细节见 [自动化报告](../doc/frontend-ui-8081.md)。

可配置 `PORTAL_UI_BASE_URL`、`PORTAL_UI_BACKEND_URL`、`PORTAL_UI_OUTPUT`；本轮默认分别为localhost:5174、localhost:8081和target/frontend-ui-8081。`PORTAL_UI_REUSE_DOC`只允许复用标题以“浏览器文档验收-”开头的测试文档。未指定时会进行真实上传、Embedding并产生新文档，结束后停用。
