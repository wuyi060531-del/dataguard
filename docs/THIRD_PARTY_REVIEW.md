# 第三方依赖与许可证

Boot 4 迁移后实际解析出 96 个 Maven 依赖（含测试依赖）。下表读取自对应的 POM（必要时沿用 parent）和 JAR 里的声明。本表不覆盖构建插件和基础镜像，也不是许可证兼容性的结论。

需要注意的两处：

- **MySQL Connector/J** 声明为 GPLv2 加 Universal FOSS Exception。
- **H2** 是 MPL/EPL 双许可。

项目许可证还没有选定。选定前需要核对上游原文；如果将来分发二进制、JRE 或镜像，还要附上对应的 NOTICE 和系统组件声明。

| Maven 坐标 | 版本 | POM 声明 | JAR 声明数量 |
| --- | --- | --- | --- |
| org.springframework.boot:spring-boot-starter-webmvc | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-starter | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-starter-logging | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| ch.qos.logback:logback-classic | 1.5.38 | [EPL-2.0](https://www.eclipse.org/legal/epl-v20.html); [LGPL-2.1-only](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html) | 0 |
| ch.qos.logback:logback-core | 1.5.38 | [EPL-2.0](https://www.eclipse.org/legal/epl-v20.html); [LGPL-2.1-only](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html) | 0 |
| org.apache.logging.log4j:log4j-to-slf4j | 2.25.5 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| org.apache.logging.log4j:log4j-api | 2.25.5 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| org.slf4j:jul-to-slf4j | 2.0.20 | [MIT](https://opensource.org/license/mit) | 1 |
| org.springframework.boot:spring-boot-autoconfigure | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| jakarta.annotation:jakarta.annotation-api | 3.0.0 | [EPL 2.0](https://www.eclipse.org/legal/epl-2.0); [GPL2 w/ CPE](https://www.gnu.org/software/classpath/license.html) | 2 |
| org.yaml:snakeyaml | 2.6 | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 0 |
| org.springframework.boot:spring-boot-starter-jackson | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-jackson | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| tools.jackson.core:jackson-databind | 3.1.7 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| com.fasterxml.jackson.core:jackson-annotations | 2.21 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| tools.jackson.core:jackson-core | 3.1.7 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 5 |
| org.springframework.boot:spring-boot-starter-tomcat | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-starter-tomcat-runtime | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.apache.tomcat.embed:tomcat-embed-core | 11.0.27 | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| org.apache.tomcat.embed:tomcat-embed-websocket | 11.0.27 | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| org.springframework.boot:spring-boot-tomcat | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-http-converter | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework:spring-context | 7.0.10 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework:spring-web | 7.0.10 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework:spring-beans | 7.0.10 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| io.micrometer:micrometer-observation | 1.17.2 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| io.micrometer:micrometer-commons | 1.17.2 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| org.springframework.boot:spring-boot-webmvc | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-servlet | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework:spring-webmvc | 7.0.10 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework:spring-aop | 7.0.10 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework:spring-expression | 7.0.10 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-starter-validation | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-validation | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.apache.tomcat.embed:tomcat-embed-el | 11.0.27 | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| org.hibernate.validator:hibernate-validator | 9.1.5.Final | [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 1 |
| jakarta.validation:jakarta.validation-api | 3.1.1 | [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 0 |
| org.jboss.logging:jboss-logging | 3.6.3.Final | [Apache License 2.0](https://repository.jboss.org/licenses/apache-2.0.txt) | 0 |
| com.fasterxml:classmate | 1.7.3 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| org.springframework.boot:spring-boot-starter-jdbc | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-jdbc | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-sql | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-transaction | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-persistence | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework:spring-tx | 7.0.10 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework:spring-jdbc | 7.0.10 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| com.zaxxer:HikariCP | 7.0.2 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 0 |
| org.slf4j:slf4j-api | 2.0.20 | [MIT](https://opensource.org/license/mit) | 1 |
| org.apache.commons:commons-csv | 1.14.1 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| commons-io:commons-io | 2.20.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| commons-codec:commons-codec | 1.21.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| com.h2database:h2 | 2.5.250 | 未在本机 POM/parent 找到，分发前人工核对 | 0 |
| com.mysql:mysql-connector-j | 9.7.0 | The GNU General Public License, v2 with Universal FOSS Exception, v1.0 | 43 |
| org.springframework.boot:spring-boot-starter-webmvc-test | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-starter-jackson-test | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-starter-test | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-test | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-test-autoconfigure | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| com.jayway.jsonpath:json-path | 2.10.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 0 |
| jakarta.xml.bind:jakarta.xml.bind-api | 4.0.5 | [Eclipse Distribution License - v 1.0](http://www.eclipse.org/org/documents/edl-v10.php) | 2 |
| jakarta.activation:jakarta.activation-api | 2.1.4 | [EDL 1.0](http://www.eclipse.org/org/documents/edl-v10.php) | 2 |
| net.minidev:json-smart | 2.6.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 0 |
| net.minidev:accessors-smart | 2.6.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 0 |
| org.ow2.asm:asm | 9.7.1 | [BSD-3-Clause](https://asm.ow2.io/license.html) | 0 |
| org.assertj:assertj-core | 3.27.7 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 0 |
| net.bytebuddy:byte-buddy | 1.18.15 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 3 |
| org.awaitility:awaitility | 4.3.0 | [Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 0 |
| org.hamcrest:hamcrest | 3.0 | [BSD-3-Clause](https://raw.githubusercontent.com/hamcrest/JavaHamcrest/master/LICENSE) | 1 |
| org.junit.jupiter:junit-jupiter | 6.0.3 | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html) | 2 |
| org.junit.jupiter:junit-jupiter-api | 6.0.3 | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html) | 2 |
| org.opentest4j:opentest4j | 1.3.0 | [The Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 1 |
| org.junit.platform:junit-platform-commons | 6.0.3 | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html) | 2 |
| org.apiguardian:apiguardian-api | 1.1.2 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 1 |
| org.junit.jupiter:junit-jupiter-params | 6.0.3 | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html) | 3 |
| org.junit.jupiter:junit-jupiter-engine | 6.0.3 | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html) | 2 |
| org.junit.platform:junit-platform-engine | 6.0.3 | [Eclipse Public License v2.0](https://www.eclipse.org/legal/epl-v20.html) | 2 |
| org.mockito:mockito-core | 5.23.0 | [MIT](https://opensource.org/licenses/MIT) | 1 |
| net.bytebuddy:byte-buddy-agent | 1.18.15 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| org.objenesis:objenesis | 3.3 | [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| org.mockito:mockito-junit-jupiter | 5.23.0 | [MIT](https://opensource.org/licenses/MIT) | 1 |
| org.skyscreamer:jsonassert | 1.5.3 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 0 |
| com.vaadin.external.google:android-json | 0.0.20131108.vaadin1 | [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0) | 0 |
| org.springframework:spring-core | 7.0.10 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| commons-logging:commons-logging | 1.3.6 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | 2 |
| org.jspecify:jspecify | 1.0.1 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 0 |
| org.springframework:spring-test | 7.0.10 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.xmlunit:xmlunit-core | 2.11.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | 0 |
| org.springframework.boot:spring-boot-webmvc-test | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-web-server | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-resttestclient | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-starter-restclient-test | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-starter-restclient | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-restclient | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-http-client | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |
| org.springframework.boot:spring-boot-restclient-test | 4.1.2 | [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0) | 2 |

## 可选的主许可证

可以在 [MIT](https://opensource.org/license/mit)、[Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0)、[GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) 中选择。这几个都没有对所有分发形式逐一确认过兼容，选定之前需要先核对 Connector/J 的 Universal FOSS Exception 和各依赖的原文。
