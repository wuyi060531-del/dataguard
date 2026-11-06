# 安全维护与边界

截至 2026-11-06，正式工程已迁移至 Spring Boot **4.1.2**、Framework **7.0.10**、Jackson **3.1.7**、Tomcat **11.0.27**，保留 Java 21。以[官方系统要求](https://docs.spring.io/spring-boot/system-requirements.html)和[迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)核实，实际版本以 pom 和解析图为准；未来维护状态仍需定期复审。

## 已解决的例外

原 Framework 6.2.19 的 [CVE-2026-47890](https://spring.io/security/cve-2026-47890/)（SSE fragments）和 [CVE-2026-47884](https://spring.io/security/cve-2026-47884/)（XsltView）均在 7.0.9 修复；当前实际解析 7.0.10，不再依赖功能不可达的限期豁免。config/security-exceptions.json 已为空。历史 3.5 两项例外及对照扫描保留在 [旧证据](docs/evidence/boot4-release/dependencies.json) 和 Git 历史，不延长或伪称当时已修复。

移除旧 Jackson 2/Tomcat 10/Log4j 维护覆盖，采用 Boot 4 BOM。Jackson annotations 仍使用 com.fasterxml 命名空间是上游设计，实际不含 Jackson 2 databind/core。项目没有引入 Spring Security；BOM 管理其版本不等于应用已具备认证。

唯一新增覆盖为 H2 **2.5.250**：完整回归真实复现 BOM 默认 2.4.240 在关闭 DDL 连接后 CHECK IN 比较器引用已关闭会话。官方 [#4302](https://github.com/h2database/h2database/issues/4302) 和 [2.5.250 发布](https://github.com/h2database/h2database/releases/tag/version-2.5.250)确认修复。保留所有 CHECK/外键/事务测试，未以移除约束绕过失败。

## 实际审计及其范围

[96 个解析 Maven 坐标的 OSV 查询](docs/evidence/boot4-release/dependencies.json)：0 发现、0 例外，包含测试依赖。扫描不覆盖构建插件、JDK、基础镜像 OS 或未知漏洞，不构成零风险承诺。分页、异常响应、网络失败均须失败，不能删除依赖或增加无依据豁免来得到绿色结果。

```bash
bash scripts/mvn.sh dependency:list -DoutputFile=target/dependencies.txt
python3 scripts/dependency-audit.py --dependencies target/dependencies.txt --output target/dependency-audit.json
python3 scripts/check-security.py
```

## 应用安全边界

仅本机单用户、单实例同步演示，无账户认证。Host/Origin 校验不是权限系统，不应把服务暴露公网。默认和 Compose HTTP 都绑定 loopback；MySQL 容器无宿主端口映射。

上传保存随机受控名称，导入只能来自配置根目录；防穿越、符号链接和 HTML 注入。SQL 使用参数化绑定，密码从环境读取，不提交 .env、数据库或真实个人数据。5 MiB/10000 记录和字段、问题、报告预算超限整单失败，不静默截断。运行数据库和上传文件必须一起备份。

公开使用白名单无历史候选，原历史含私人学习资料和路径，不重写原历史。有限模式检查不能代替所有者隐私复核。主许可证、依赖声明及远程 CI 仍需发布前决策/验证。详见 [工程验收](docs/VALIDATION.md)。
