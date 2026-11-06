# DataGuard — Java 数据质量检测平台

Java 21 / Spring Boot 4.1.2 / Jackson 3 / JDBC 小型同步后端。上传订单 CSV，执行可定位的确定性规则，生成 JSON/HTML 报告，保存检测任务、失败历史与重启后的结果。本仓库目录名可以自选，产品名始终为 DataGuard。

**公开候选源码，许可证尚待所有者选择。** 未声明任何开源授权；依赖声明见 [第三方审查](docs/THIRD_PARTY_REVIEW.md)。工程在 AI 支持下实现，需求和取舍由项目所有者决定；不能据此声称所有模块由个人独立实现。

## 几分钟演示

准备 JDK 21、Bash、curl 或 wget、unzip，在本目录执行（首次需要联网）：

```bash
bash scripts/mvn.sh clean verify
bash scripts/run.sh
```

打开 http://127.0.0.1:8080 → **导入模拟订单** → **开始检测** → 打开 JSON 或 HTML 报告。无需 MySQL 即可运行；默认 H2 文件库与上传副本分别位于 var/db、var/uploads，停止服务后同时备份两者。CLI：

```bash
bash scripts/run.sh inspect data/raw/orders.csv reports
```

固定样本为 40 条合成订单，无真实个人信息，期望 **29 正常、11 异常、11 项问题、72.50%**。数值由程序计算，一条记录可能有多个问题，WARNING 也计入异常记录。规则覆盖必填、全部重复参与行、类型、范围、真实日期、合法状态、跨字段总额；详见 [数据字典](data/README.md)。

## 测试与证据边界

本轮迁移回归为 94 项 Java 测试通过，其中 6 项连接隔离 MySQL 8.4.11，验证真实约束、跨批次回滚和中断恢复。未提供专用测试库环境时默认 **88 通过、6 跳过**，不能称为 MySQL 已验证。学习材料不混入本快照。

若已有空的专用 MySQL 8 测试库，设置 DATAGUARD_MYSQL_TEST_URL / USERNAME / PASSWORD 后运行 verify。测试结束仅清理本次 UUID 数据；不要指向业务库。应用连接 MySQL 使用 DATAGUARD_DB_URL / USERNAME / PASSWORD，并设置 SPRING_PROFILES_ACTIVE=mysql，数据库密码均由环境提供。

演示自动验收（服务启动后）：

```bash
python3 scripts/demo-check.py create --state .setup/demo-state.json
# 正常停止并重启同一服务后：
python3 scripts/demo-check.py verify --state .setup/demo-state.json
```

包含成功报告、无效路径、空文件失败历史、失败报告 409，以及重启后报告和文件复用。这里不包含断电灾备或备份恢复证明。

## Docker / CI

Dockerfile、Compose、GitHub Actions 已提供。本轮 Docker 29.8.2 / Compose 5.5.0 实际构建和启动通过，HTTP、命名卷重启、数据库故障 unhealthy/503 与恢复已验证；**远程 Actions 尚未运行**。具备 Docker 后复制 compose.env.example 为 .env，填写两组不同随机密码，再执行：

```bash
docker compose up --build --wait
# 停止服务，保留命名卷：
docker compose down
```

不要提交 .env，不用删除卷修复登录。应用容器以非 root、只读根文件系统运行，HTTP 仅映射本机；健康检查会查询数据库支持的 API。CI 强制真实 MySQL 测试零跳过，并检查容器重启前后报告及文件复用；配置不等于远程全绿。

## 架构与限制

Controller 校验 HTTP/DTO → Service 保存数据与执行任务 → QualityRule 接口组合确定性规则 → Repository/JdbcTemplate → H2/MySQL 四张表（datasets、inspection_tasks、inspection_reports、quality_issues）。报告、问题、成功状态在同一事务提交；失败独立记账，启动时把旧 RUNNING 转为 FAILED/INTERRUPTED。

输入上限 5 MiB、10000 条记录、单字段 4096 Java 字符；问题、诊断和报告也有限额，超限整单失败。按记录去重统计异常，绝不自动清洗原 CSV。仅单机、单用户、单实例，无认证、调度、水平扩容或 AI API；不应直接部署公网。

原两项 Spring 例外已随 Framework 7.0.10 升级移除，96 个解析依赖经 OSV 查询无发现；范围和限制见 [安全说明](SECURITY.md)。本项目可展示工程设计和真实测试证据，不能声称为生产安全平台。

[API 与错误示例](docs/API.md) · [安全审计](SECURITY.md) · [架构](docs/architecture.md) · [演示与匿名截图](docs/DEMO.md) · [真实验收](docs/VALIDATION.md)
