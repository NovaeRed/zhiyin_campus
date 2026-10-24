# main 更新后的原有改动审查与真实验收（2026-10-24）

基线：main / origin/main `0ee00fd`。逐文件比较 `target/workspace-backup-before-main-20261024-112930/manifest.json` 中95份已备份文件，忽略CRLF/LF差异，只有两个文件内容不同。原有Java业务代码、Vue组件、配置与迁移SQL均已包含在main，无需重复覆盖。

## 本次合入

- `pom.xml`：恢复Testcontainers的PostgreSQL/JUnit测试依赖，Surefire/Failsafe 3.5.3及测试schema路径。缺少这些配置会导致保留的测试无法编译或集成测试不按约定执行；不改变生产依赖的测试作用域。
- `frontend/scripts/verify-ui.mjs`：文档详情先切换到“切片正文”标签，再展开切片，适配原文默认预览。
- 未回退Vue迁移、未恢复React归档、未修改凭证或数据库结构。原备份、其他本地辅助文档/脚本继续保留，提交仅包含本次修复、报告清理及引用更新。

## 构建和重启

`mvn package -B -ntp -DskipTests`通过，包含117份生产源码与23份测试源码编译；跳过执行单元测试，不使用Mock声称RAG通过。`node --check frontend/scripts/verify-ui.mjs`、`git diff --check`通过。

确认18082原监听PID30012命令行属于本项目后停止，使用新构建JAR启动PID27340。运行目录：`target/main-reviewed-runtime-20261024-113329`，仍供15175的Vue前端代理。没有停止其他端口。

## 实际验证与未解决问题

- `scripts/Test-IntegrationHttp.ps1 -BaseUrl http://localhost:18082 -Output target/main-reviewed-contract`：17/17真实HTTP契约检查通过。外层启动命令误读旧LASTEXITCODE报告失败，检查脚本本身完整结束，原始checks.json全部true。
- `node frontend/scripts/verify-course-nature.mjs --live`：最终5/5通过。数学全学程、大四必修（610008）、选修六门、历史刷新、无浏览器错误。结果：`target/course-nature-browser/results.json`。本轮首跑遇到503，不能将最终通过描述为从未失败。
- `ParentChildHttpIT`真实HTTP首次在语义理解503后中止。随后顺序复测完成13/31题，13题均通过，queryId730～742；第14题“2024级计算机科学与技术毛泽东思想和中国特色社会主义理论体系概论是几学分？”超过HTTP客户端180秒后超时，后续18题未执行，完整门禁失败。结果：`target/main-reviewed-rag-recheck/http-results.json`与Failsafe报告。没有用Mock替代、删除失败结果或降低验收标准。
- 当前配置聊天模型为`deepseek-v4-flash-0731`。供应商最小请求HTTP200，使用项目真实SDK和语义提示词独立诊断也成功；这不能证明所有请求稳定，也不能据此认定初始503或长请求的根因。模型调用链的超时/取消仍需专项诊断；本次未擅自更换模型或隐式自动重试用户请求。
- 真实验收新增专用账号、会话和问答轨迹，发生模型调用；未上传/重建知识文档或重新Embedding。复测知识表指纹前后均为`8fd0a1bd60341db2121dd87d4d4ff148`，knowledgeUnchanged=true，详见`target/main-reviewed-rag-recheck/http-database-safety.json`。

结论：原有业务实现已在main，遗漏的测试配置与定位修正已恢复，最新后端已重启；基础契约与必修/选修页面检查通过，但完整RAG验收因真实超时未通过，不能宣称全面测试成功。
