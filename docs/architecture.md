# 架构与源码索引

Java 21、Spring Boot 4.1.2、Jackson 3、Spring JDBC，单实例同步处理订单 CSV。没有引入 Spring Security、ORM、消息队列或分布式任务。默认 H2 用于本机演示，MySQL 使用独立 profile/schema；H2 全绿不能代替 MySQL 验证。

| 技术点 | 源码位置（相对根目录） | 设计与证据 |
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

四表为 datasets、inspection_tasks、inspection_reports、quality_issues。先记录任务，再计算；计算成功后的报告、所有批次问题和成功状态在同一数据库事务内保存。异常使该事务整体回滚，随后单独记录 FAILED，避免回滚也删除失败证据。文件系统不参与 JDBC 事务，使用副本完整性检查及补偿清理，不声称跨资源原子性。

## 五个面试追问

1. SUCCEEDED 为何仍可能有 11 条异常记录？HTTP 201、执行成功和数据合格分别表示什么？
2. 用 Set 边读边标记为什么可能漏掉首条重复订单？Map 两遍扫描的空间代价是什么？
3. 第 251 条问题失败时，报告、此前批次、任务各应保留什么？FAILED 为什么不与报告一起回滚？
4. H2 验证与真实 MySQL 验证分别证明什么？本次 H2 CHECK 跨连接故障说明了什么？
5. 上传文件成功、数据库保存失败或文件之后被改动时如何处理？为何 JDBC 事务不能自动回滚文件？

## 性能证据的边界

历史相同机器/数据/250 条批次实验：1000 问题事务中位 99.56 → 41.12 ms，问题 INSERT 1001 → 5 次，优化为 Connector/J rewriteBatchedStatements。该数据来自升级前 V1.1，非本轮 Boot 4 重新测得，不用于声称生产吞吐。本轮重新验证跨批次事务与上限，但没有扩展压力测试或宣称高并发。

旧 Jackson 2 报告兼容、API/数据库测试及运行限制见 [验收](VALIDATION.md)、[API](API.md) 和 [安全](../SECURITY.md)。
