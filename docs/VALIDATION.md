# Boot 4 迁移与真实验收

2026-11-06：正式工程由 Boot 3.5.16 迁移到 4.1.2，保留 Java 21、业务/API 契约和数据库结构。开发使用受控分支并保留原稳定检查点；公开候选仅导出白名单源码，不含旧 Git 历史、个人学习记录或运行数据库。

| 项目 | 已执行的结果 |
| --- | --- |
| 正式 Maven Wrapper clean verify | 94 项、0 失败/错误/跳过，11.068 s；其中 6 项真实 MySQL 8.4.11 |
| JUnit/API | Jupiter 6；HTTP 上传、导入、报告、非法参数、路径、超限、HTML、错误响应与任务状态 |
| 事务 | MySQL 真实约束、250+50 跨批问题写入后故障回滚；失败历史与中断恢复 |
| 旧数据兼容 | 实际 Boot 3.5/Jackson 2 写入的旧 H2 2.3.232 库副本由 Boot 4/H2 2.5.250 打开；报告、失败历史与文件再检测保留 |
| 序列化 | 新增 3 项测试验证旧 JSON 的计数、小数、枚举、定位、ISO 时间与错误字段 |
| 重启持久化 | 真正停止/启动 MySQL 与 Java，报告/数据集/文件保留，旧 RUNNING 恢复 FAILED/INTERRUPTED |
| Docker/Compose | OrbStack 上 Docker 29.8.2、Compose 5.5.0 实际构建、启动；MySQL 8.4.13、HTTP create/verify、卷保留重启、数据库停止 unhealthy/503 与恢复 healthy 均通过 |
| 独立公开候选 | 无 Git 历史的 90 文件候选独立 clean verify：94 项全通过、零跳过，20.777 s；6 项连接容器 MySQL 8.4.13。导出的工具单测 11 项通过，候选自身 JAR 的 HTTP 和 Java 进程重启通过 |
| 工具 | 19 项 Python 单元测试、actionlint、Compose YAML 与安全规则检查通过 |
| 依赖 | 96 个解析 Maven 坐标 OSV 查询 0 发现/0 例外；不覆盖插件/JDK/镜像 OS/未知漏洞 |

机器可核查摘要：[测试](evidence/boot4-release/tests.json)、[依赖查询](evidence/boot4-release/dependencies.json)、[旧库兼容](evidence/boot4-release/legacy-compatibility.json)、[MySQL 重启](evidence/boot4-release/mysql-restart.json)、[容器实测](evidence/boot4-release/docker.json)、[独立候选回执](evidence/boot4-release/public-candidate.json)。

## 迁移中确实出现的故障

- Jackson 3 删除 findValuesAsText，改用等价 findValuesAsString；测试客户端移至 Boot 4 模块，显式启用 TestRestTemplate。没有删测试或业务。
- HTTP 413 在 Framework 7 的枚举改名 CONTENT_TOO_LARGE，数值和对外语义不变，保留断言。
- BOM 默认 H2 2.4.240 的 CHECK IN 跨连接真实回归使 8 项测试失败，改用官方修复版 2.5.250；不取消约束或固定旧会话逃避验证。
- Docker 构建没有 unzip，Wrapper 退取 TAR.GZ 触发 ZIP 校验失败；在构建层安装 unzip，未取消校验。
- 容器仅连 internal 网络，内部探针正常却宿主端口拒绝；应用添加普通 bridge 供 loopback 映射，MySQL 正式配置仍仅 internal。

## 运行与发布边界

GitHub Actions 配置已完成静态检查，**远程尚未运行**，没有创建 remote 或 push。公开源码不是公网服务；无账号认证，不声称生产可用。最多 5 MiB / 10000 记录，同步执行，未做断电灾备、网络分区或高并发验证。

原两项 Framework 6.2.19 限期例外随升级移除；安全工具查询无发现不等于所有层无漏洞。镜像 digest 固定、OS 扫描、备份/schema 版本迁移仍可后续按需完善，不为简历扩大架构。

根 LICENSE 尚待所有者选择，并复核第三方分发声明。候选需人工隐私审阅，自动有限模式检查不是无泄漏保证。真实源码面试地图见 [架构](architecture.md)，不能据此声称本人已独立掌握所有实现。
