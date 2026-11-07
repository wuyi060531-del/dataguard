# 浅色数据审查工作台：阶段一验收

本轮为 Codex 工程支持，不构成个人学习完成。只改善原生 HTML/CSS/JS 与独立 HTML 报告的展示，不改 Controller、Service、Repository、规则、DTO、SQL 或评分公式。不启动阶段二的筛选、分页、失败反馈与下载交互改造。

## 实际截图与测量

截图来自本机隔离 H2、合成订单和真实 Chrome 156 渲染。桌面 1366×900，手机模拟 390×844；不是设计稿。截图不包含浏览器账户栏或本机路径。

| 内容 | 修改前 | 修改后 |
| --- | --- | --- |
| 桌面首屏 | [前](evidence/frontend-stage1/before-desktop.png) | [后](evidence/frontend-stage1/after-desktop.png) |
| 手机顶部 | [前](evidence/frontend-stage1/before-mobile-top.png) | [后](evidence/frontend-stage1/after-mobile-top.png) |
| 手机报告概览 | [前](evidence/frontend-stage1/before-mobile-overview.png) | [后](evidence/frontend-stage1/after-mobile-overview.png) |
| 手机问题列表 | [前](evidence/frontend-stage1/before-mobile-issues.png) | [后](evidence/frontend-stage1/after-mobile-issues.png) |
| 手机独立 HTML | [前](evidence/frontend-stage1/before-mobile-html.png) | [后](evidence/frontend-stage1/after-mobile-html.png) |
| 零记录 | [前](evidence/frontend-stage1/before-zero-records.png) | [后](evidence/frontend-stage1/after-zero-records.png) |
| 仅 WARNING | [前](evidence/frontend-stage1/before-warning-only.png) | [后](evidence/frontend-stage1/after-warning-only.png) |

| 测量 | 修改前 | 修改后 |
| --- | --- | --- |
| 桌面报告距页面顶部 | 683.81px | 437.13px |
| 手机订单编号单元格宽度 | 40.38px | 302px（值与标签位于同一完整条目） |
| 390px 独立 HTML 整页宽度 | 501px | 390px |

独立 HTML 保留完整表格及原始快照，通过带键盘焦点的局部滚动容器查看横向内容，不用隐藏溢出来丢弃数据。主页面手机问题条目保留原有表格表头，并显示对应字段标签。桌面首屏包含文件上下文、执行状态、质量结论、五项指标与问题入口。

## 语义与回归

- “执行：检测完成”表示任务执行并保存成功，采用中性色；与错误、警告或通过当前规则检查分开。
- 错误用红色、警告用琥珀色，同时有文字说明。异常记录和问题数量保持不同概念。
- 零记录显示“无数据可评估”、质量分“—”、历史“未评估”，并解释 API 的 100% 为系统约定；后端 JSON 和评分不变。
- 40 条原始样本仍为 29 正常、11 异常、11 项问题、72.50%。未清洗或覆盖原始文件。
- 两项新增 JUnit 测试覆盖非空合格、错误及警告展示；已有零记录和输出转义断言保留并增强。

实际执行：

| 验证 | 结果 |
| --- | --- |
| 修改前 `bash scripts/mvn.sh -o -B -ntp clean verify` | 94 项：88 通过、6 MySQL 跳过，0 失败/错误 |
| 修改后同一命令 | 96 项：90 通过、6 MySQL 跳过，0 失败/错误 |
| 修改前真实浏览器基线 | 7 项数据/流程检查通过；同时记录已知布局缺陷，不把旧布局说成达标 |
| 修改后真实浏览器 | 23 项通过：[完整结果](evidence/frontend-stage1/after-checks.json) |
| Python 工具回归 | 21 项通过，含新增截图白名单与伪装 PNG 拒绝测试 |
| 安全守卫及自检 | 通过；文本检查不等于完整漏洞或图像隐私审计 |

浏览器覆盖正常样本、零记录、仅警告、非空合格、空文件失败、中文文件名、超长标识和 HTML 注入文本；验证页面宽度、完整标识、质量语义、JSON 分数及无未处理 JS 异常。所有状态均来自实际 HTTP/JDBC 操作，不 Mock API。本轮没有重新运行本机 MySQL/Docker，后续远程结果必须按对应 Actions 单独记录。

## 复现浏览器验收

只对隔离演示环境运行：脚本会创建合成数据集和任务。不要将 base-url 指向个人正式数据库。无需安装新前端框架；Python 工具依赖可放入项目忽略的虚拟环境：

```bash
python3 -m venv .setup/ui-check-venv
.setup/ui-check-venv/bin/python -m pip install websocket-client==1.9.0
bash scripts/mvn.sh clean verify
```

另一个终端启动隔离服务（保持进程运行，结束后 Ctrl+C）：

```bash
JAVA_TOOL_OPTIONS='-Xmx256m -XX:ActiveProcessorCount=2' \
DATAGUARD_DB_PATH="$PWD/.setup/ui-stage1-demo/db/dataguard" \
DATAGUARD_STORAGE_ROOT="$PWD/.setup/ui-stage1-demo/uploads" \
DATAGUARD_IMPORT_ROOT="$PWD/data/raw" PORT=18098 bash scripts/run.sh
```

用独立配置目录启动本机 Chrome，不连接个人浏览器会话：

```bash
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless=new --remote-debugging-port=0 \
  --user-data-dir="$PWD/.setup/ui-stage1-chrome" \
  --no-first-run --no-default-browser-check --disable-background-networking about:blank
```

在第三个终端执行（输出另存忽略目录，保留版本中的原验收截图）：

```bash
.setup/ui-check-venv/bin/python scripts/check-ui-stage1.py \
  --base-url http://127.0.0.1:18098 \
  --cdp-port "$(head -1 .setup/ui-stage1-chrome/DevToolsActivePort)" \
  --output .setup/ui-stage1-recheck --fixtures .setup/ui-stage1-fixtures --phase after
```

验收脚本要求自身建立的 API 任务和真实页面一致。`--phase before` 只用于旧版本基线，不能用它代替新版验收。手机为 Chrome 模拟，未声称真机 Safari、屏幕阅读器或完整 WCAG 验证。CI 现有 Java/MySQL/容器验收不包含本地 Chrome 脚本。

## 发布与停止边界

本轮用户补充授权验收后同步已有 Private 仓库，仅同步独立候选增量，不推原工程历史。发布工具只允许本页列出的 14 张 PNG；已逐项确认来自隔离合成数据，文件名、截图内容和 JSON 均无个人资料、学习答案、凭据或数据库。图片需要视觉审核，不能声称文本守卫扫描了图片隐私。保留 LICENSE 现状，不更改可见性。

失败详情在视口外、问题列表仅前 100 项、原始英文规则、预览式报告链接等仍是既有边界；本轮没有暗中完成阶段二。Day01、八挑战及学生掌握等级保持原样。
