# library-seat — Spring Boot 业务后端

本目录是 **源码**。文档已全部集中到仓库的 `docs/` 下，这里只留一个入口。

| 文档                                                                                       | 内容                                                                                                                                      |
|--------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| [../docs/library-seat/01-后端说明.md](../docs/library-seat/01-后端说明.md)                 | 职责边界、包结构、启动步骤、32 个接口、鉴权、定时任务、验证状态                                                                           |
| [../docs/library-seat/03-OneNET接入指南.md](../docs/library-seat/03-OneNET接入指南.md)     | OneNET 控制台配置、物模型、上下行报文、token 签名、联调顺序                                                                               |
| [../docs/library-seat/04-后端开发注意事项.md](../docs/library-seat/04-后端开发注意事项.md) | ★ **改代码前必读**：snake_case 连带影响、HTTP 状态码约定、日志脱敏红线、定时任务的坑、JDK 27 工具链 / Java 17 目标 / Lombok + Byte Buddy |

全仓库总览与快速开始：[../README.md](../README.md)

---

## 三行速查

```bash
# 建库建表 + 种子数据（管理员 admin/Admin@123）
mysql -uroot -p < src/main/resources/db/01_schema.sql
mysql -uroot -p < src/main/resources/db/02_seed.sql
# 老库（user 表还没有 student_no / password 列）额外跑一次幂等迁移：
# mysql -uroot -p < src/main/resources/db/03_migrate_student_account.sql

# 启动（端口 8080；环境变量见 01-后端说明 §4.2）
mvn spring-boot:run

# 测试（55 个用例；第一次跑必须联网下 surefire 插件）
mvn test
```

启动后用 `curl -i http://localhost:8080/api/seats` 验证 —— **返回 401 才是正常**。

> ⚠️ **工具链是 JDK 27，编译目标是 Java 17** —— 这是两个东西，别一起改。
> 编译目标必须留在 17：Boot 3.2.5 的 ASM 认不了 class major 71，设 27 时编译能过但 **应用起不来**。
> 而因为跑在 **JDK 27** 上，Boot 3.2.5 的两个默认版本必须覆盖，否则一个静默出错、一个测试变红：
> `<lombok.version>1.18.48</lombok.version>`（默认 1.18.32 在 27 上会 **静默不生成 getter/setter**）
> 与 `<byte-buddy.version>1.18.14</byte-buddy.version>`（默认 1.14.13 只认到 class 66）。
> 详见 [../docs/library-seat/04-后端开发注意事项.md](../docs/library-seat/04-后端开发注意事项.md) §6.1。
