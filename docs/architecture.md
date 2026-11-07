# 架构与源码入口

Java 21、Spring Boot 4.1.2、Jackson 3、Spring JDBC，单实例同步处理订单 CSV。没有使用 Spring Security、ORM、消息队列或分布式任务。默认的 H2 用于本机演示，MySQL 通过独立的 profile 和 schema 使用。H2 的测试通过不能代替 MySQL 的验证。

| 主题 | 源码位置 | 设计说明 |
| --- | --- | --- |
| 规则接口与引擎 | src/main/java/dataguard/core/QualityRule.java、QualityEngine.java | Rule 验证并产出定位问题；Engine 组织解析、规则、预算及汇总，HTTP/数据库不进入规则 |
| 集合与判重 | src/main/java/dataguard/core/DuplicateOrderRule.java | 统计非空订单号频次，第二遍标记所有重复参与行；平均线性扫描，不能只标后续记录 |
| 数据模型 | src/main/java/dataguard/core/DataRecord.java、QualityReport.java | record、枚举、集合保存输入快照和不可变检测结果 |
| HTTP/DTO | src/main/java/dataguard/api/DataGuardController.java | 请求验证与状态码；ApiExceptionHandler 统一错误，LocalRequestFilter 限制本机请求边界 |
| 服务编排 | src/main/java/dataguard/service/TaskService.java | RUNNING 后同步检测，质量问题可形成 SUCCEEDED 报告，无法完成则 FAILED |
| JDBC/事务 | src/main/java/dataguard/persistence/InspectionRepository.java | 参数绑定、外键/CHECK，报告+全部问题+成功状态在同一 TransactionTemplate 内提交 |
| 批写/回滚 | src/test/java/dataguard/persistence/MySqlIntegrationTest.java | 250 条一批；300 问题在 250+50 两批写入后注入 FK 故障，整个事务回滚，失败另行记账 |
| 输入预算/恢复 | src/main/java/dataguard/service/FileStorage.java、TaskService.java | 受控副本、SHA-256、5 MiB/10000 记录，报告/诊断预算；启动恢复旧 RUNNING |

## 一条请求调用链

POST /api/tasks → DataGuardController → TaskService.startTask → DatasetService/FileStorage 验证副本 → QualityEngine.inspect → QualityRule.validate → InspectionRepository/JdbcTemplate → MySQL InnoDB 四张表。

四表为 datasets、inspection_tasks、inspection_reports、quality_issues。先记录任务，再计算。计算成功后，报告、所有批次的问题和成功状态在同一个数据库事务里保存。出现异常时事务整体回滚，然后单独记录 FAILED，这样回滚不会连失败记录一起删掉。文件系统不在 JDBC 事务里，所以用副本完整性检查和补偿清理来处理，并不保证文件和数据库之间的原子性。

## 几个值得想一想的设计问题

1. SUCCEEDED 为何仍可能有 11 条异常记录？HTTP 201、执行成功和数据合格分别表示什么？
2. 用 Set 边读边标记为什么可能漏掉首条重复订单？Map 两遍扫描的空间代价是什么？
3. 第 251 条问题失败时，报告、此前批次、任务各应保留什么？FAILED 为什么不与报告一起回滚？
4. H2 验证与真实 MySQL 验证分别证明什么？本次 H2 CHECK 跨连接故障说明了什么？
5. 上传文件成功、数据库保存失败或文件之后被改动时如何处理？为何 JDBC 事务不能自动回滚文件？

## 性能数据的说明

升级前（V1.1）在同一台机器、同一份数据上、以 250 条为一批做过对比：写入 1000 个问题的事务，耗时中位数从 99.56 ms 降到 41.12 ms，问题的 INSERT 语句从 1001 次降到 5 次，做法是启用 Connector/J 的 `rewriteBatchedStatements`。这组数据不是 Boot 4 之后重新测的，也不能说明生产环境的吞吐。Boot 4 之后重新验证了跨批次事务和各项上限，但没有做压力测试。

旧版 Jackson 2 报告的兼容性、API 和数据库测试，以及运行限制，见 [验证记录](VALIDATION.md)、[API](API.md) 和 [安全说明](../SECURITY.md)。
