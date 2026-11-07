# 三分钟演示

先按 README 的步骤构建并启动服务，默认地址是 http://127.0.0.1:8080。示例数据是固定的 40 条合成订单，不含真实个人信息，原 CSV 不会被修改。

1. 在页面点“导入模拟订单”，记下 `datasetId`；也可以直接上传 `data/raw/orders.csv`。
2. 点“开始检测”，查看任务状态和 JSON/HTML 报告。预期结果：总数 40、正常 29、异常 11、问题 11、质量分 72.50%。第一个问题定位在数据记录 7 / 物理行 8。
3. 注意“异常记录”和“问题数”不一样：一行可以同时触发多条规则。可以按必填、重复、范围、日期等类别查看。
4. 上传一个空 CSV，任务应为 `FAILED / EMPTY_FILE`，读取它的报告会返回 409，而不是一份空的成功报告。
5. 正常关闭服务，用同一个数据库和上传目录重新启动，再查询旧任务：报告、失败记录和文件重新检测都还在。

自动化检查（先启动服务）：

```bash
python3 scripts/demo-check.py create --state .setup/demo-state.json
# 正常停止并重新启动同一个服务后：
python3 scripts/demo-check.py verify --state .setup/demo-state.json
```

请求和响应示例见 [API](API.md)，验证范围见 [VALIDATION](VALIDATION.md)。项目不提供断电恢复、生产备份还原或多实例的保证。

## 截图

仓库里的截图在 `docs/evidence/frontend-stage1/`，来自隔离环境中的合成数据。自己截图时，用没有登录账号的浏览器窗口，只保留页面内容，裁掉账号栏、书签、桌面路径和终端历史，也不要上传 `.env`、数据库或完整的浏览器截图。
