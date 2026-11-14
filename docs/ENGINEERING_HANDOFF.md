# 工程验收与前端交接（2026-11-14）

本次基于公开 main bcbabb0e9b4fca7cd26d3234e640d3f7a4a08cb3，未带入私人工作区历史。
已有视觉改版、问题原值展示和规则点击定位均保留。Codex 协助审查、修复和验证。

## 修复与证据

真实 Chrome 157 + Java 后端，390px 视口的订单编号单元格只有 150px，已有
check-ui-stage1 检查出现 22/23 通过。手机问题卡片改为订单编号、字段各占一行，
宽度恢复 310px；23/23 通过，页面和独立 HTML 报告均无整体横向溢出。
只改一条手机 CSS grid-template-areas，不重做视觉设计，不改变 API 或检测规则。

后端现有功能已足够：受控上传副本、严格 CSV/UTF-8/预算验证、确定性规则、报告与
问题及成功状态的同事务提交、失败记录、启动时中断恢复。未复现需要重写的缺陷。
质量分、问题数、异常行数和执行状态仍保持各自语义。

## 本机实际验证

- `bash scripts/mvn.sh -B -ntp clean verify`：90 通过、6 MySQL 跳过。
- 配置专用 MySQL 8.4.13 后 `bash scripts/mvn.sh -B -ntp verify`：96 通过，零失败/跳过。
  使用独立 Docker 服务和 dataguard_test 空库，不访问业务库。
- `python3 -m unittest discover -s scripts -p '*_test.py'`：13 通过（包括 Node 交互回归）。
- `python3 scripts/check-security.py --self-test` 和无参数检查：通过。
- `python3 scripts/demo-check.py create --base-url http://127.0.0.1:18081 --state .setup/review-state.json`，
  重启同一个隔离 H2/文件目录后执行 `verify`：报告、失败记录、数据集保留，文件可再次检测。
  样本仍为 40/29/11/11，质量分 72.50；空文件任务失败，报告返回 409。
- `python3 scripts/check-ui-stage1.py --base-url http://127.0.0.1:18081 --cdp-port 19223 --output .setup/ui-evidence --fixtures .setup/ui-fixtures --phase after`：23 通过。
  该脚本需要 websocket-client 和单独启动的 Chrome 调试实例；只能指向隔离演示库。
- 修改 CSS 后重新 package，并重启服务后执行上述浏览器检查，未使用模拟 API。
- `bash scripts/mvn.sh dependency:list -DoutputFile=target/dependencies.txt` 与
  `python3 scripts/dependency-audit.py --dependencies target/dependencies.txt --output target/dependency-audit.json`：
  96 个组件，OSV 查询无未处理项或例外；仅代表核验时的已知漏洞。
- `docker build --pull=false --tag dataguard:engineering-review .`：通过。
  独立 Compose project、18082 回环端口，使用本次镜像与 MySQL 8.4.13：启动健康，
  demo-check create 通过；停止并重启 app/mysql（保留命名卷），demo-check verify 通过。
  没有连接正式服务或复用用户数据。

以上命令省略了本机 JDK、Node 路径和专用数据库口令。CI 状态以 PR 当前提交的 Actions
为准；本机测试不能代替远端容器构建。没有声称 Windows、真实手机或生产负载验证。

## 给 Claude 的入口

- 启动：`bash scripts/mvn.sh clean verify`，`bash scripts/run.sh`；浏览器 `http://127.0.0.1:8080`。
- 主要文件：`src/main/resources/static/index.html`、`app.css`、`app.js`。
- 流程：上传 CSV / 导入模拟订单 → 开始检测 → 规则分布 → 问题筛选/分页 → JSON/HTML → 历史任务。
- API 和状态码以 [API.md](API.md) 为准。数据集 `POST /api/datasets`，任务 `POST /api/tasks`，
  报告 `GET /api/tasks/{id}/report`；HTML 由 ReportRenderer 生成，不是静态页面副本。
- 合成样本 40 行与 README 的 200 行截图不是同一份数据，继续标明来源，避免展示口径混淆。
- 保留 textContent、HTML 转义、无数据/警告/失败状态、实际值、手机全宽字段和本机请求限制。
  修改视觉后重跑现有 23 项浏览器检查，不能只用截图或桩 API 宣称联调完成。

## 保留的限制

单用户、单实例本机工具，无登录和公网隔离。文件与数据库不是一个原子事务；已做副本
校验及清理，但未验证断电或备份恢复。依赖更新 PR 单独审查，不与本次修复捆绑。
