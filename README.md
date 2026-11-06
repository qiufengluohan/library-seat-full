# 机电图书馆 · 座位预约与学习管理系统

> 毕业设计 ｜ 智慧图书馆座位智能管理系统（「四端统一」架构的**软件三端**）
> 微信小程序 + Vue3 Web 管理端 + Spring Boot 业务云端

本仓库是这套系统的**完整软件交付物**。系统由设备端（STM32 + ESP8266 硬件，**不在此仓库**）、
微信小程序端、Web 管理端、Spring Boot 业务后端四方协同，本仓库包含其中**三个软件端的全部源码与配置**，
以及全部文档（集中在 `docs/`）。

---

## 一、系统架构

```
┌─────────────────┐         MQTT          ┌──────────────────────┐
│  设备端（硬件）   │ ───────────────────▶ │   OneNET Studio      │
│ STM32 + ESP8266 │  压力/红外/RFID       │  iot-api.heclouds    │
│  FSR402 / HC-SR505 / RC522            │  .com（物模型+设备） │
└─────────────────┘                      └──────────┬───────────┘
                                                    │ HTTP 推送
                                                    ▼
                                          ┌─────────────────────────┐
                                          │  Spring Boot 业务后端     │
                                          │  library-seat  ( :8080 ) │
                                          │  全部业务判断的唯一大脑    │
                                          └───┬───────────────┬──────┘
                               WebSocket /ws/seats      │  MySQL 8.0
                                      ┌──────────────┐  │
                                      ▼              ▼  ▼
                              ┌──────────────┐  ┌──────────────┐
                              │ Vue3 Web 管理端 │  │ 微信小程序端   │
                              │ library-admin  │  │ jidian-library│
                              │   ( :3000 )    │  │(微信开发者工具)│
                              └──────────────┘  └──────────────┘
```

**职责边界（编码规范与方案文档强制约定）：**

- 设备端只负责**感知**（压力 / 红外 / RFID 读卡）与**执行**（蜂鸣器 / OLED），不持有任何业务状态；
- OneNET 只承担「设备 → 后端」这一段数据通道，凭证仅存在于后端进程内；
- **小程序与管理端都只与自建 Spring Boot 后端通信**，不直连 OneNET、不持有任何设备密钥；
- 所有业务判断（座位状态机、预约超时、暂离超时、假占座判定、鉴权、统计）都在后端；
- 前端只做「状态码 → 展示」的映射，**绝不**根据压力值 / 红外状态反推座位状态。

---

## 二、四端一览

| 端 | 目录 | 端口 / 载体 | 干什么 | 文档 |
| --- | --- | --- | --- | --- |
| **后端** | `library-seat/` | 8080 | 唯一业务大脑：32 个接口、状态机、定时任务、OneNET 双向转发 | [说明](docs/library-seat/01-后端说明.md) · [注意事项](docs/library-seat/04-后端开发注意事项.md) · [OneNET 接入](docs/library-seat/03-OneNET接入指南.md) |
| **管理端** | `library-admin/` | 3000 | 管理员后台：实时大屏、座位 / 设备 / RFID / 违规统计 | [说明](docs/library-admin/01-管理端说明.md) · [注意事项](docs/library-admin/04-管理端开发注意事项.md) |
| **小程序** | `jidian-library/` | 微信开发者工具 | 学生端：看座位、预约、暂离/回座/离座 | [说明](docs/jidian-library/01-小程序说明.md) · [注意事项](docs/jidian-library/04-小程序开发注意事项.md) |
| 设备端 | 不在本仓库 | STM32F103C8T6 | 采集传感器、本地融合判定、报警、RFID 读卡 | → `素材/设备端方案_OneNET版.txt` |

另外三份**不专属某一端**的文档直接放在 `docs/` 根下：[数据库设计](docs/01-数据库设计.md)（9 张表逐字段）、
[OneNET 接入与物模型设计](docs/02-OneNET接入与物模型设计.md)（物模型 / 报文 / STM32 要点 /「设备事实 vs 业务状态」的分界）、[答辩要点与演示脚本](docs/03-答辩要点与演示脚本.md)。

---

## 三、功能特性

- **座位预约**：阅览室座位图、确认预约、当前预约态管理。
- **RFID 签到**：由设备读卡触发（读卡 → OneNET → 后端），小程序端**没有签到按钮**；
  学生端只做**暂离 / 回座 / 离座**。
- **实时状态**：WebSocket `/ws/seats` 推送座位与设备变化，小程序与管理端**共用同一套消息格式**。
- **座位状态 5 值**：`FREE 空闲 / RESERVED 已预约 / USING 使用中 / AWAY 暂离 / ALARM 异常`；
  **设备离线**是独立 `online` 标志，仅渲染「离线」角标，**不覆盖**业务状态。
- **预约状态机**：`RESERVED → USING → COMPLETED`，含 `CANCELLED / TIMEOUT` 终态；
  超时规则：预约 15 分钟未签到 → `TIMEOUT`，暂离 30 分钟未回座 → `COMPLETED`。
- **Web 管理端**：实时大屏、座位管理、设备管理（改 ADC 阈值 / 测试蜂鸣器 / 强制释放 / 消警）、
  RFID 绑卡、违规与学习统计、操作日志。
- **后端约束**：JSON 统一 `snake_case`；鉴权仅 `STUDENT / ADMIN` 两角色，不做 RBAC；
  HTTP **只有 401 和 500 用真实状态码**，其余走 `200 + body.code`。

---

## 四、技术栈

| 端 | 技术 |
| --- | --- |
| **后端** `library-seat` | Spring Boot 3.2.5 · **JDK 27 工具链 + Java 17 编译目标**（见下方说明）· MyBatis-Plus · 原生 WebSocket · JWT · Maven · MySQL 8.0 |
| **管理端** `library-admin` | Vue 3（`<script setup>`）· Element Plus · Pinia · Vue Router 4 · Axios · ECharts 5 · Vite 5 · Sass |
| **小程序** `jidian-library` | 原生 WXML / WXSS / JavaScript · 学号密码登录 + 自助注册 · 9 个页面 · 基础库 3.17.3 |

> ⚠️ **后端的 Java 版本是「分开的两件事」，不要合并**：
> **工具链与运行环境是 JDK 27**，但**编译目标是 Java 17**（`pom.xml` 的 `<java.version>17</java.version>`）。
> 原因：Spring Boot 3.2.5 内置的 ASM 认不了 Java 27 的字节码（class major 71），
> 目标设 27 时**编译能过、应用一启动就失败**。另外因为跑在 JDK 27 上，
> Lombok 与 Byte Buddy 两个 Boot 默认版本也必须覆盖。细节见
> [后端开发注意事项 §6.1](docs/library-seat/04-后端开发注意事项.md)。

后端规模（已实测）：主代码 102 个 `.java`、测试 6 个类 55 用例、HTTP 接口 32 个、
Controller 10 个、Service 13+13、Entity + Mapper 9+9。

---

## 五、目录结构

```
library-seat-full/
├── docs/                        全部文档（唯一的文档入口）
│   ├── 01-数据库设计.md          四端共用契约：9 张表逐字段
│   ├── 02-OneNET接入与物模型设计.md  四端共用契约：物模型 / 报文 / STM32 要点
│   ├── 03-答辩要点与演示脚本.md      项目级：演示脚本与论文对应关系
│   ├── 04-小程序端移植为Web端方案.md  历史方案备查：改造为 Web 端（已在另一副本实施，本仓库仍是小程序版）
│   ├── jidian-library/          小程序端：01-说明 / 04-注意事项
│   ├── library-seat/            后端：01-说明 / 03-OneNET接入指南 / 04-注意事项
│   └── library-admin/           管理端：01-说明 / 04-注意事项
├── jidian-library/              微信小程序端（微信开发者工具导入这一层）
│   ├── app.js / app.json / app.wxss / sitemap.json
│   ├── utils/                   config / request / ws / api / util（5 个文件）
│   ├── components/empty/  images/  pages/（9 个页面）
│   └── project.config.json  project.private.config.json
├── library-seat/                Spring Boot 后端
│   ├── src/main/java/...        102 个 .java
│   ├── src/main/resources/application.yml
│   ├── src/main/resources/db/   01_schema.sql / 02_seed.sql / 03_migrate_student_account.sql
│   └── pom.xml
├── library-admin/               Vue3 Web 管理端
│   ├── src/                     api / components / constants / router / stores / utils / views
│   ├── .env.development  .env.production   只放地址，不放密钥
│   └── vite.config.js           /api 与 /ws 代理到 localhost:8080
├── 素材/                        毕设材料归档（与运行无关）
│   ├── 智慧图书馆座位智能管理系统_四端统一最终方案.txt
│   ├── 智慧图书馆座位智能管理系统_实际编码规范_最终版.txt
│   ├── 设备端方案_OneNET版.txt
│   ├── 毕设命题及功能.txt  草稿.txt
│   ├── 开题报告.docx  开题报告模板.docx
│   └── 设备导出_1790212444.xlsx
├── model-Wh08f3Q71p.json        OneNET 物模型导出文件（控制台可一键导入）
├── LICENSE                      MIT
└── README.md                    本文件（全仓库总文档）
```

> 仓库已通过 `.gitignore` 排除构建产物（`target/`、`node_modules/`）与 IDE 目录（`.idea/`）。
> `素材/` 仅作毕设材料归档，不参与运行。

---

## 六、环境要求与交给别人运行

### 6.1 最低 JDK 版本：**17**（不能用 Java 8）

后端基于 **Spring Boot 3.2.5**，而 **Spring Boot 3.x 强制要求 JDK 17 起步**
（JDK 8 只支持到 Spring Boot 2.7.x）。因此：

- 本项目的 `pom.xml` 编译目标是 **Java 17**（产物是 class major **61**）；
- 用 **Java 8** 运行会直接失败，报：

  ```
  java.lang.UnsupportedClassVersionError: org/springframework/boot/...
    class file version 61.0
  ```

  这不是代码问题，是框架的硬性门槛 —— **改配置解决不了**。

**推荐版本：JDK 17 或 21（都是 LTS）。** 开发机用的 JDK 27 也能跑，但没必要。

> 📌 如果你（或接收方）**只能使用 Java 8**，需要用本仓库移植出的 Java 8 版本
> `library-seat-full-java8`（Spring Boot 2.7.18 + `javax.*` 命名空间），见 §6.4。

### 6.2 三种运行方式（按接收方条件选）

| 方式 | 接收方需要装什么 | 你要做什么 | 适用场景 |
| --- | --- | --- | --- |
| **A. 源码运行** | JDK 17+、Maven 3.8+、MySQL | 直接给源码 | 要改代码 / 做二次开发 |
| **B. fat jar 运行** ⭐ | 只要 **JRE 17+**（无需 JDK、无需 Maven） | `mvn clean package -DskipTests` 后把 jar 给他 | 只做演示、答辩现场最省事 |
| **C. 降级到 Java 8** | JDK 8、Maven | 用 `library-seat-full-java8` 那份 | 接收方环境锁死 Java 8 |

### 6.3 打包成 fat jar 交付（方式 B 详细步骤）

```bash
cd library-seat
mvn clean package -DskipTests
# 产物：target/library-seat-1.0.0.jar（内含全部依赖，可直接运行）
```

接收方只需要 **JRE 17 或更高**，然后：

```bash
# Windows
set MYSQL_PASSWORD=你的库密码
java -jar library-seat-1.0.0.jar

# Linux / macOS
export MYSQL_PASSWORD=你的库密码
java -jar library-seat-1.0.0.jar
```

前提：对方机器上要有 MySQL 8，并已导入
`library-seat/src/main/resources/db/01_schema.sql` 与 `02_seed.sql`。

> ⚠️ **交付前务必清理** `.idea/` —— 里面有明文的 `MYSQL_PASSWORD`（Run Configuration）。
> 打包 zip 时它会被带上。详见 §10。

### 6.4 Java 8 移植版

`library-seat-full-java8` 是专门给「只能用 Java 8」的场景准备的移植版，主要差异：

| 项 | 原版 | Java 8 版 |
| --- | --- | --- |
| Spring Boot | 3.2.5 | **2.7.18**（最后一条支持 JDK 8 的线） |
| JDK 要求 | 17+ | **1.8** |
| 命名空间 | `jakarta.*` | `javax.*`（Servlet / Validation / Annotation） |
| MyBatis-Plus | `mybatis-plus-spring-boot3-starter` | `mybatis-plus-boot-starter` |
| MySQL 驱动 | `com.mysql:mysql-connector-j` | 同左，版本交给 Boot 2.7.18 的 BOM（8.0.33） |
| OkHttp | 4.12.0 | 交给 Boot 2.7.18 的 `okhttp-bom`（4.9.3） |
| Lombok | 1.18.48（手工升，为了跑在 JDK 27 上） | 同为 **1.18.48**（Boot 默认的 1.18.30 在新 JDK 上会静默不生成 getter/setter） |
| Byte Buddy | 1.18.14（手工升，Mockito 用） | 用 Boot 2.7.18 默认版本 |
| 语法 | record / 文本块 / switch 表达式等 | 已全部改写为 Java 8 兼容写法 |

**语法改写清单**（这些在原版里都是 Java 9+ 特性，Java 8 编不过）：

| 原版写法 | 出现版本 | Java 8 版改写为 |
| --- | --- | --- |
| `record Xxx(...)` | Java 16 | 普通 `final class` + 构造器（方法名保持 `x()` 风格不变调用点） |
| `"""` 文本块 | Java 15 | 字符串拼接 / `\"` 转义 |
| `case X -> ...` switch 表达式 | Java 14 | 传统 `switch` 语句 + `return`/`break` |
| `instanceof X xx` 模式匹配 | Java 16 | 先 `instanceof` 再显式强转 |
| `List.of()` / `Map.of()` / `Set.of()` | Java 9 | `Collections.emptyList()` / `singletonMap()` / `emptySet()` / `EnumSet.of()` |
| `String.isBlank()` | Java 11 | `s.trim().isEmpty()` |
| `Stream.toList()` | Java 16 | `collect(Collectors.toList())` |
| `URLEncoder.encode(s, Charset)` | Java 10 | `URLEncoder.encode(s, "UTF-8")` + catch `UnsupportedEncodingException` |

另外两处**框架版本**差异（非语法）：

- `NoResourceFoundException`（Spring 6.1 才有）→ `NoHandlerFoundException`（Spring 5.3），
  并需在 `application.yml` 里同时开 `spring.mvc.throw-exception-if-no-handler-found: true`
  与 `spring.web.resources.add-mappings: false` 才生效。
- 移植时顺手修掉了原版 yml 里 `port: 8080` 错嵌在 `tomcat:` 下的问题。

**保留不变**：数据库表结构、接口路径、请求/响应字段、业务逻辑、状态机口径
—— 与原版**完全一致**，前端与管理端无需改动，可直接对接。

**构建方式**（Java 8 版有自己的 Maven 配置，避开主配置里强制 17 的 profile）：

```bash
cd library-seat-full-java8/library-seat
mvn -s .mvn-build/settings.xml clean package -DskipTests
```

> ⚠️ 必须带 `-s .mvn-build/settings.xml`。用户主 `settings.xml` 里有一个
> `activeByDefault=true` 的 `jdk17` profile，会把编译目标强制设成 17，
> 与 Java 8 版的 pom 冲突。

**JAR 运行**（需要 JDK 8 运行时）：

```bash
java -jar target/library-seat-1.0.0.jar
```

**已验证**：104 个 class 全为 major 52 / `mvn test` 55 全通过 / fat jar 30 MB /
用真实 Temurin JDK 1.8.0_504 启动成功（`Started ... in 4.023 seconds`）/
注册·登录·鉴权·404 五类接口冒烟行为与原版逐条一致。

---

## 七、快速开始

### 0. 准备环境

| 软件 | 版本 | 用途 |
| --- | --- | --- |
| JDK | **17 或更高**（开发机用的是 27；编译目标 17） | 跑后端 |
| Maven | 3.8+ | 从源码构建后端 |
| MySQL | 8.0 | 数据库 |
| Node.js | 18+ | Web 管理端 |
| 微信开发者工具 | 稳定版 | 小程序 |
| cpolar / ngrok（可选） | — | 把 OneNET 推送打到本地后端 |

> ⚠️ 后端**不能用 Java 8 运行**，详见下一节。

### 1. 后端 `library-seat`（端口 8080）

```bash
# ① 建库建表 + 种子数据（管理员 admin/Admin@123、座位 A4-203 → 设备 SEAT_001）
cd library-seat/src/main/resources/db
mysql -uroot -p < 01_schema.sql
mysql -uroot -p < 02_seed.sql

# ② 配置环境变量后启动
cd ../../..
export MYSQL_PASSWORD="你的库密码"
export JWT_SECRET="至少32字节的随机串"
export ONENET_PUSH_SECRET="随机串"                  # 挂公网隧道时强烈建议
export ONENET_API_KEY="OneNET产品级api-key"          # 下行（改阈值/蜂鸣器）才需要
cd library-seat
mvn spring-boot:run
# 或：java -jar target/library-seat-1.0.0.jar
```

启动后 `curl -i http://localhost:8080/api/seats` 应返回 **401** ——
这正说明 JWT 拦截正常、服务活着，不是故障。

> **后端在本机用 IntelliJ IDEA 启动**：打开 `library-seat/` 文件夹，运行 `LibrarySeatApplication`
> （Run Configuration 里已配好 `MYSQL_PASSWORD` / `ONENET_API_KEY` 等环境变量，端口走 `application.yml` 的 8080）。
> IDEA 只认自己的 Run Configuration，不读任何 IDE 外的配置文件。

### 2. Web 管理端 `library-admin`（端口 3000）

```bash
cd library-admin
npm install        # 必须！node_modules 被 .gitignore 忽略，新副本里没有
npm run dev        # http://localhost:3000，/api 与 /ws 已代理到 8080
```

登录：管理员 `admin` / `Admin@123`（开发演示用，**公网部署前必须改**）。

### 3. 小程序 `jidian-library`

1. 打开 `jidian-library/utils/config.js`，把 `server.baseUrl` 填成后端地址
   （本机 `http://localhost:8080`；真机走穿透域名 `https://xxxxxx.r27.cpolar.top`）；
2. 微信开发者工具 → 导入项目 → **目录选 `jidian-library/` 这一层**
   （`project.config.json` 就在里面，没有 `miniprogramRoot`）；
3. 开发期在「详情 → 本地设置」勾选**不校验合法域名**；
   上线前把域名同时加进微信公众平台的 **request 合法域名** 与 **socket 合法域名**
   —— 那是**两个输入框**，只配第一个会让"数据都对、但座位不自动刷新"。

---

## 八、环境变量（全部走环境变量，绝不写进 yml 提交）

| 变量 | 必须？ | 默认 | 说明 |
| --- | --- | --- | --- |
| `MYSQL_PASSWORD` | 是（密码非 `root` 时） | `root` | 数据库密码 |
| `JWT_SECRET` | 建议 | 公开占位串 | ≥32 字节随机串，否则可伪造 token |
| `ONENET_PUSH_SECRET` | 强烈建议（挂公网时） | 空 | 推送接口共享密钥；留空则 `/api/onenet/**` 免鉴权 |
| `ONENET_API_KEY` | 下行需要 | 空 | OneNET 产品级 api-key（属性下发 / 服务调用） |
| `ONENET_PRODUCT_ID` | 不用 | `Wh08f3Q71p` | — |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_USERNAME` | 不用 | `localhost` / `3306` / `root` | — |

配给后端有两条路，**各配各的**：IDEA 的 Run Configuration（本机唯一在用的方式）、
临时的 `export` / `$env:`。细节见
[后端注意事项 §5](docs/library-seat/04-后端开发注意事项.md)。

---

## 九、文档索引

**改代码 / 排查问题之前，先看对应端的「开发注意事项」** —— 上面每一条都是踩出来的。

| 文档 | 内容 |
| --- | --- |
| [后端说明](docs/library-seat/01-后端说明.md) | 职责边界、包结构、启动步骤、32 个接口、鉴权、定时任务、验证状态 |
| [后端开发注意事项](docs/library-seat/04-后端开发注意事项.md) | ★ snake_case 连带影响、HTTP 状态码约定、日志脱敏红线、定时任务的坑、JDK 27 工具链 / Java 17 目标 / Lombok + Byte Buddy |
| [OneNET 接入指南](docs/library-seat/03-OneNET接入指南.md) | 控制台配置、物模型、上下行报文、token 签名、联调顺序 |
| [管理端说明](docs/library-admin/01-管理端说明.md) | 技术栈、6 个页面、状态颜色、数据契约、运行方式 |
| [管理端开发注意事项](docs/library-admin/04-管理端开发注意事项.md) | ★ 请求层两条判断线、409 静默、401 必须带出 message、Vite 代理的坑 |
| [小程序说明](docs/jidian-library/01-小程序说明.md) | 目录结构、9 个页面、状态口径、14 个接口通道、怎么跑起来、排错 |
| [小程序开发注意事项](docs/jidian-library/04-小程序开发注意事项.md) | ★ 网络出口收敛、合法域名两个入口、WebSocket 注意事项、自检脚本 |
| [数据库设计](docs/01-数据库设计.md) | 9 张表 SQL、字段口径、表与页面的对应关系 |
| [OneNET 接入与物模型设计](docs/02-OneNET接入与物模型设计.md) | 物模型功能点表、报文格式、AT 指令、STM32 要点 |
| [答辩要点与演示脚本](docs/03-答辩要点与演示脚本.md) | 演示脚本、亮点讲解词、可能被追问的问题、论文对应关系 |
| [小程序端移植为 Web 端方案](docs/04-小程序端移植为Web端方案.md) | 历史方案备查：原生 HTML+JS 移植实施手册（该方案已在另一份副本实施；本仓库仍是小程序版） |
| [双座位改造方案](docs/05-双座位改造方案.md) | 一块板管两个座位：拆属性 `_1/_2`、三色指示灯、按键方案、`seat_no` 迁移与实施顺序 |
| `素材/…_四端统一最终方案.txt` | 四端职责边界与页面清单（原始方案） |
| `素材/…_实际编码规范_最终版.txt` | 接口与字段契约、状态机、分层铁律（**权威口径**） |
| `model-Wh08f3Q71p.json` | OneNET 物模型导出文件；控制台「导入物模型」可一键还原 |

---

## 十、安全与部署提示

- **本仓库不含任何真实密钥**：`application.yml` 所有凭证均为 `${ENV:默认值}` 占位符；
  `.gitignore` 已排除 `.idea/` 与构建产物；
- ⚠️ 本机密码等敏感值只写在 **IDEA 的 Run Configuration** 里（`.idea/workspace.xml`，已被 gitignore）。
  **打包成 zip 交付前记得清一遍 `.idea/`**，别把本机配置带给别人；
- 默认管理员密码 `Admin@123`、JWT 默认占位串均为**开发演示用**，公网部署前必须改；
- 学生账号走**学号 + 密码自助注册**（`POST /api/auth/register` / `login`），密码在后端
  BCrypt 校验（`pom.xml` 已引 `spring-security-crypto`）。**本系统不接微信登录**，
  因此不涉及微信公众平台的 IP 白名单 —— 那东西只管「后端带 AppSecret 调微信接口」这一件事；
- `ONENET_PUSH_SECRET` 为空时 `/api/onenet/**` 处于免鉴权状态，**仅本机联调可接受**；
- 后端 `server.port` 默认 `8080`；若被占用，改 `application.yml` 即可
  （管理端 `vite.config.js` 的 `BACKEND` 同步调整）。

---

## 十一、License

[MIT](./LICENSE) © 2026 qiufengluohan — 物联网工程技术专业
