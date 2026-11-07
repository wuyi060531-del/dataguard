# 安全说明

## 当前版本

截至 2026-11-06：Spring Boot **4.1.2**、Framework **7.0.10**、Jackson **3.1.7**、Tomcat **11.0.27**，Java 21。版本以 `pom.xml` 和依赖解析结果为准，参考[官方系统要求](https://docs.spring.io/spring-boot/system-requirements.html)和[迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)。后续维护状态需要定期复查。

## 依赖与漏洞

之前为 Framework 6.2.19 登记过两项限期例外：[CVE-2026-47890](https://spring.io/security/cve-2026-47890/)（SSE fragments）和 [CVE-2026-47884](https://spring.io/security/cve-2026-47884/)（XsltView），这两项在 7.0.9 修复。现在解析到的是 7.0.10，`config/security-exceptions.json` 已经是空的。旧的例外和对照扫描保留在[旧证据](docs/evidence/boot4-release/dependencies.json)和 Git 历史里。

升级后去掉了对 Jackson 2、Tomcat 10 和 Log4j 的手动版本覆盖，改用 Boot 4 的 BOM。Jackson 的 annotations 包仍在 `com.fasterxml` 命名空间下，这是上游的设计，项目里并没有 Jackson 2 的 databind 或 core。项目没有使用 Spring Security，BOM 里管理了它的版本不代表应用有认证。

唯一手动指定的版本是 H2 **2.5.250**：完整回归测试中，BOM 默认的 2.4.240 在 DDL 连接关闭后，`CHECK IN` 比较器会引用已关闭的会话。官方 [#4302](https://github.com/h2database/h2database/issues/4302) 和 [2.5.250 发布说明](https://github.com/h2database/h2database/releases/tag/version-2.5.250)确认了这个修复。所有 CHECK、外键和事务测试都保留，没有靠删除约束来通过。

[96 个解析出的 Maven 坐标做了 OSV 查询](docs/evidence/boot4-release/dependencies.json)（含测试依赖）：0 发现、0 例外。扫描不覆盖构建插件、JDK、基础镜像的操作系统和未知漏洞，所以这不是零风险的承诺。查询出错、分页异常或网络失败都会让检查失败，不能靠删依赖或加没有依据的例外来得到绿色结果。

```bash
bash scripts/mvn.sh dependency:list -DoutputFile=target/dependencies.txt
python3 scripts/dependency-audit.py --dependencies target/dependencies.txt --output target/dependency-audit.json
python3 scripts/check-security.py
```

## 应用的安全边界

这是只在本机单用户使用的演示，没有账号认证。Host 和 Origin 校验只用来防浏览器跨站调用本机接口，不是权限系统，**不要把服务暴露到公网**。默认配置和 Compose 的 HTTP 端口都只绑定回环地址，MySQL 容器没有映射到宿主机端口。

- 上传的文件用随机名保存；导入只能读取配置的根目录，防目录穿越和符号链接。
- 报告中的内容做了 HTML 转义。
- SQL 全部用参数绑定，密码从环境变量读取，不提交 `.env`、数据库或真实个人数据。
- 5 MiB / 10000 条记录的上限，以及字段、问题数、报告大小的预算，超限时整个任务失败，不会悄悄截断。
- 运行用的数据库和上传目录要一起备份。

仓库是用白名单从原工程导出的，没有带过去原来的历史，因为原历史里有私人学习资料和本机路径。项目以 [MIT](LICENSE) 发布，第三方依赖保留各自的许可证，见 [THIRD_PARTY_REVIEW](docs/THIRD_PARTY_REVIEW.md)。验证细节见 [VALIDATION](docs/VALIDATION.md)。
