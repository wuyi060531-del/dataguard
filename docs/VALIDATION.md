# 验证记录

这里记录实际执行过的检查和结果，没有执行的内容不会写成通过。机器可核查的摘要在 `docs/evidence/boot4-release/` 下。

## 当前状态

最新提交 `e4b4c1f` 的 [GitHub Actions](https://github.com/wuyi060531-del/dataguard/actions/runs/42326900890) 成功，包含两个任务：`verify`（Java 21 加真实 MySQL 的完整测试，要求 MySQL 集成测试零跳过）和 `container-smoke`（容器构建与启动检查）。

前端改版（见 [frontend-stage1.md](frontend-stage1.md)）在本机的结果：96 项 Java 测试中 90 项通过，6 项需要 MySQL 的测试因本机没有 MySQL 而跳过；本机 Chrome 的 23 项展示与交互检查全部通过。

## Spring Boot 4 迁移（2026-11-06）

从 Spring Boot 3.5.16 迁移到 4.1.2，Java 21、业务和 API 契约、数据库结构都没有变。

| 项目 | 结果 |
| --- | --- |
| Maven `clean verify` | 94 项，0 失败、0 错误、0 跳过，11.068 秒；其中 6 项连接真实的 MySQL 8.4.11 |
| HTTP/API | 上传、导入、报告、非法参数、路径、超限、HTML、错误响应和任务状态 |
| 事务 | MySQL 真实约束；250 + 50 两批问题写入后注入故障，整体回滚；失败历史和中断恢复 |
| 旧数据兼容 | 用 Boot 3.5 / Jackson 2 写出的旧 H2 2.3.232 库副本，能被 Boot 4 / H2 2.5.250 打开，报告、失败历史和文件重新检测都还在 |
| 序列化 | 新增 3 项测试，验证旧 JSON 的计数、小数、枚举、定位、ISO 时间和错误字段 |
| 重启 | 真正停止再启动 MySQL 和 Java 进程，报告、数据集和文件保留，旧的 RUNNING 任务恢复为 FAILED/INTERRUPTED |
| Docker / Compose | Docker 29.8.2、Compose 5.5.0 实际构建并启动；MySQL 8.4.13，HTTP 创建和校验、保留数据卷重启、数据库停止后 unhealthy/503 以及恢复后 healthy 都通过 |
| 独立公开副本 | 无 Git 历史的 90 个文件，独立 `clean verify`：94 项全通过、零跳过，20.777 秒 |
| 工具脚本 | 19 项 Python 单元测试、actionlint、Compose YAML 和安全规则检查通过 |
| 依赖漏洞 | 96 个解析出的 Maven 坐标做 OSV 查询，0 发现、0 例外 |

详细记录：[测试](evidence/boot4-release/tests.json) · [依赖查询](evidence/boot4-release/dependencies.json) · [旧库兼容](evidence/boot4-release/legacy-compatibility.json) · [MySQL 重启](evidence/boot4-release/mysql-restart.json) · [容器](evidence/boot4-release/docker.json) · [公开副本](evidence/boot4-release/public-candidate.json)

### 迁移中遇到的问题

- Jackson 3 删除了 `findValuesAsText`，换成等价的 `findValuesAsString`；测试客户端移到 Boot 4 的新模块，并显式启用 `TestRestTemplate`。没有删测试或业务代码。
- Framework 7 把 HTTP 413 的枚举改名为 `CONTENT_TOO_LARGE`，数值和对外行为不变，断言保留。
- Boot 4 默认的 H2 2.4.240 在跨连接场景下，`CHECK IN` 约束会让 8 项测试失败，所以改用官方修复版 2.5.250，没有取消约束，也没有固定旧会话来绕过。
- Docker 构建环境没有 `unzip`，Maven Wrapper 退而下载 TAR.GZ 后触发 ZIP 校验失败。改为在构建层安装 `unzip`，没有关掉校验。
- 容器只连了 internal 网络，内部探针正常，但宿主机端口被拒绝。给应用加了一个普通 bridge 网络来支持 loopback 映射，MySQL 仍然只在 internal 网络里。

## 未覆盖的范围

- 这是本机单用户的演示项目，没有账号认证，不能当作生产服务。
- 上传上限 5 MiB / 10000 条记录，检测同步执行。
- 没有测试断电恢复、网络分区和高并发。
- 依赖查询不覆盖构建插件、JDK、镜像操作系统和未知漏洞。
- 镜像摘要固定、操作系统层扫描、备份和 schema 版本迁移还可以继续完善，但这个项目不打算为此扩大架构。

安全方面的细节见 [SECURITY.md](../SECURITY.md)，源码入口见[架构](architecture.md)。
