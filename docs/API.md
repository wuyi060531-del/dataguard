# DataGuard API 演示

服务默认 http://127.0.0.1:8080。先导入固定样本：

```bash
curl -sS http://127.0.0.1:8080/api/datasets/import -H 'Content-Type: application/json' -d '{"path":"orders.csv"}'
curl -sS http://127.0.0.1:8080/api/tasks -H 'Content-Type: application/json' -d '{"datasetId":"替换为上一步返回的UUID"}'
curl -sS http://127.0.0.1:8080/api/tasks/替换为任务UUID/report
```

也可 `curl -sS http://127.0.0.1:8080/api/datasets -F 'file=@data/raw/orders.csv'` 上传。数据集返回 id、fileName、sizeBytes、sha256、createdAt；任务返回 id、datasetId、status、错误或检测计数。两种创建均返回 201，但只有任务 status 才表达执行成功与否。

成功样本报告摘要：

```json
{"totalRecords":40,"validRecords":29,"invalidRecords":11,"totalIssues":11,"qualityScore":72.50}
```

完整报告还有 fields、issuesByRule、issuesBySeverity 和 issues。每项问题包含 recordNumber、lineNumber、orderId、field、rule、message、severity、record；记录号不含表头，物理行号支持跨行 CSV。`/api/tasks/{id}/report.html` 提供独立 HTML。

空 CSV 可以先上传，但创建任务后为 FAILED/EMPTY_FILE，错误历史仍保留；查询其 report 返回 409，响应含 `code: REPORT_NOT_AVAILABLE`。导入 `../orders.csv` 返回 400；不存在任务返回 404；超大文件返回 413。错误形状为 timestamp、status、code、message、path、details，不回显内部 SQL/堆栈或文件路径。具体文案不是稳定契约。

`GET /api/tasks?limit=20&offset=0` 查询历史，可筛 datasetId；`GET /api/tasks/{id}` 查询状态；数据集支持相同分页接口。limit 为 1～100，offset 非负。任务同步执行，检测出脏数据仍可 SUCCEEDED；无法解析或持久化才 FAILED。

指定文件仅允许导入根目录直接包含的 CSV 文件名，拒绝目录穿越与符号链接。上传用 UUID 存储名，不覆盖原始文件。Host/Origin 检查只限本机访问，不替代身份认证。
