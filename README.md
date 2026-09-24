# 机电图书馆 · 座位预约与学习管理系统

> 毕业设计 ｜ 智慧图书馆座位智能管理系统（「四端统一」架构的软件三端）
> 微信小程序 + Vue3 Web 管理端 + Spring Boot 业务云端

本仓库是「机电图书馆」座位预约与学习管理系统的**完整软件交付物**。系统由设备端（STM32 + ESP8266 硬件，不在此仓库）、微信小程序端、Web 管理端、Spring Boot 业务后端四端协同，本仓库包含其中**三个软件端**的全部源码、文档与配置。

---

## 一、系统架构

```
┌─────────────────┐         MQTT          ┌──────────────────────┐
│  设备端（硬件）   │ ───────────────────▶ │   OneNET Studio      │
│ STM32 + ESP8266 │  压力/红外/RFID       │  iot-api.heclouds   │
│ 压力 红外 RFID  │  (Wh08f3Q71p)        │  .com（物模型+设备） │
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
                              │   ( :3000 )    │  │ (微信开发者工具)│
                              └──────────────┘  └──────────────┘
```

**职责边界（编码规范与方案文档强制约定）：**

- 设备端只负责**感知**（压力 / 红外 / RFID 读卡）与**执行**（蜂鸣器 / 显示），不持有任何业务状态。
- OneNET 只承担「设备 → 后端」这一段数据通道，凭证仅存在于后端进程内。
- **小程序与管理端都只与自建 Spring Boot 后端通信，不直连 OneNET、不持有任何设备密钥。**
- 所有业务判断（座位状态机、预约超时、暂离超时、违规判定）都在后端。
- 前端只做「状态码 → 展示」的映射，**绝不**根据压力值 / 红外状态反推座位状态。

---

## 二、功能特性

- **座位预约**：阅览室座位图、确认预约、当前预约态管理。
- **RFID 签到**：由设备读卡触发（读卡器 → OneNET → 后端），小程序端无签到按钮；学生端只做**暂离 / 回座 / 离座**。
- **实时状态**：WebSocket `/ws/seats` 推送座位与设备变化，小程序与管理端共用同一套消息格式。
- **座位状态 5 值**：`FREE 空闲 / RESERVED 已预约 / USING 使用中 / AWAY 暂离 / ALARM 异常`；**设备离线**是独立 `online` 标志，仅渲染「离线」角标，不覆盖业务状态。
- **预约状态机**：`RESERVED → USING → COMPLETED`，含 `CANCELLED / TIMEOUT` 终态；超时规则：预约 15 分钟未签到 → `TIMEOUT`，暂离 30 分钟未回座 → `COMPLETED`。
- **Web 管理端**：实时大屏、座位管理、设备管理（改 ADC 阈值 / 测试蜂鸣器 / 强制释放 / 消警）、RFID 绑卡、违规与学习统计、操作日志。
- **后端约束**：JSON 统一 `snake_case`；鉴权仅 `STUDENT / ADMIN` 两角色；HTTP 仅 401/500 用真实状态码，其余走 `200 + body.code`。

---

## 三、技术栈

| 端 | 技术 |
|---|---|
| **后端** `library-seat` | Spring Boot 3.2.5 · Java 17+（实测 JDK 27 可跑）· MyBatis-Plus · WebSocket · JWT · Maven · MySQL 8.0 |
| **管理端** `library-admin` | Vue 3（`<script setup>`）· Element Plus · Pinia · Vue Router 4 · Axios · ECharts 5 · Vite 5 · Sass |
| **小程序** `jidian-library` | 原生 WXML / WXSS / JavaScript · 微信登录（openid）· 8 个页面 |

后端规模（已实测）：主代码 102 个 `.java`、测试 6 个类 55 用例、HTTP 接口 31 个、Controller 10 个、Service 13+13。

---

## 四、目录结构

```
library-seat-full/
├── jidian-library/
│   └── jidian-library/          微信小程序端（微信开发者工具导入这一层）
│       ├── miniprogram/        小程序源码（8 个页面 + utils + components + images）
│       ├── preview/            全页面静态可视化预览（preview/index.html）
│       ├── docs/               6 篇设计文档（总体/数据库/OneNET/接口契约/部署/答辩）
│       ├── project.config.json 工程配置（含 8 个编译模式）
│       └── README.md
├── library-admin/              Vue3 Web 管理端
│   ├── src/  public/           vite.config.js  package.json
│   ├── .env.development  .env.production   # 只放地址，不放密钥
│   └── README.md
├── library-seat/               Spring Boot 后端
│   ├── src/  pom.xml  docs/    # 源码、构建、OneNET 接入指南
│   ├── src/main/resources/db/  01_schema.sql / 02_seed.sql
│   └── README.md
├── 素材/                       毕设方案文档与界面截图（与运行无关）
├── model-Wh08f3Q71p.json       OneNET 物模型导入文件
├── 智慧图书馆座位智能管理系统_四端统一最终方案.txt
├── 智慧图书馆座位智能管理系统_实际编码规范_最终版.txt
├── 换电脑部署说明.md            新电脑装什么 / 配什么 / 内网穿透
├── LICENSE                     MIT
└── README.md                   本文件
```

> 仓库已通过 `.gitignore` 排除构建产物（`target/*.jar`、`node_modules/` 等）。`素材/` 仅作毕设材料归档，不参与运行。

---

## 五、快速开始

### 0. 准备环境

| 软件 | 版本 | 用途 |
|---|---|---|
| JDK | 17+（实测 27 可用） | 跑后端 |
| Maven | 3.8+（可选，可用包内 jar） | 从源码构建后端 |
| MySQL | 8.0 | 数据库 |
| Node.js | 18+ | Web 管理端 |
| 微信开发者工具 | 稳定版 | 小程序 |
| cpolar / ngrok（可选） | — | 把 OneNET 推送打到本地后端 |

### 1. 后端 `library-seat`（端口 8080）

```bash
# 建库建表 + 种子数据（管理员 admin/Admin@123、座位 A4-203 → 设备 SEAT_001）
cd library-seat/src/main/resources/db
mysql -uroot -p < 01_schema.sql
mysql -uroot -p < 02_seed.sql

# 回到项目根，配置环境变量后启动
cd ../../..
export MYSQL_PASSWORD="你的库密码"
export JWT_SECRET="$([guid]::NewGuid().ToString('N') 2>/dev/null || echo 随机串)"
export WECHAT_APP_SECRET="你的小程序AppSecret"      # 真机登录才需要
export ONENET_PUSH_SECRET="随机串"                 # 挂公网隧道时强烈建议
export ONENET_API_KEY="OneNET产品级api-key"         # 下行（改阈值/蜂鸣器）才需要
cd library-seat
mvn spring-boot:run
# 或：java -jar target/library-seat-1.0.0.jar
```

启动后 `curl -i http://localhost:8080/api/seats` 应返回 `401`（JWT 拦截正常，说明服务活着）。

### 2. Web 管理端 `library-admin`（端口 3000）

```bash
cd library-admin
npm install
npm run dev          # 自动开浏览器 http://localhost:3000，/api 与 /ws 已代理到 8080
```

登录：管理员 `admin` / `Admin@123`（开发演示用，**上线前必须改**）。

### 3. 小程序 `jidian-library`

1. 打开 `jidian-library/jidian-library/miniprogram/utils/config.js`，把 `server.baseUrl` 填成后端地址
   （本机 `http://localhost:8080`，真机调试填局域网 IP `http://192.168.x.x:8080`）。
2. 微信开发者工具 → 导入项目 → 目录选 `jidian-library/jidian-library` 这一层。
3. 开发期在「详情 → 本地设置」勾选「不校验合法域名」；上线前把域名加入微信公众平台 **request 合法域名** 与 **socket 合法域名**。

---

## 六、环境变量（全部走环境变量，绝不写进 yml 提交）

| 变量 | 必须？ | 默认 | 说明 |
|---|---|---|---|
| `MYSQL_PASSWORD` | 是（密码非 `root` 时） | `root` | 数据库密码 |
| `JWT_SECRET` | 建议 | 公开占位串 | ≥32 字节随机串，否则可伪造 token |
| `WECHAT_APP_SECRET` | 真机登录必须 | 空 | 微信登录，留空则登录报「未配置」 |
| `ONENET_PUSH_SECRET` | 强烈建议（挂公网时） | 空 | 推送接口共享密钥，留空则免鉴权 |
| `ONENET_API_KEY` | 下行需要 | 空 | OneNET 产品级 api-key（属性下发 / 服务调用） |
| `WECHAT_APP_ID` | 一般不用 | `wx0384c7d1e1e2d16d` | 换小程序才改 |
| `ONENET_PRODUCT_ID` | 不用 | `Wh08f3Q71p` | — |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_USERNAME` | 不用 | `localhost` / `3306` / `root` | — |

> 完整的新电脑部署、内网穿透（cpolar）、微信 IP 白名单与合法域名配置，见 **`换电脑部署说明.md`**。

---

## 七、文档索引

| 文档 | 内容 |
|---|---|
| `library-seat/README.md` | 后端骨架、31 个接口清单、鉴权、定时任务、OneNET 接入、验证状态 |
| `library-admin/README.md` | 管理端技术栈、页面、状态颜色、运行方式 |
| `jidian-library/jidian-library/README.md` | 小程序 8 页面、核心机制、账号与校园卡 |
| `library-seat/docs/OneNET接入指南.md` | OneNET 控制台配置、上行/下行报文、联调顺序 |
| `智慧图书馆座位智能管理系统_四端统一最终方案.txt` | 四端职责边界与页面清单 |
| `智慧图书馆座位智能管理系统_实际编码规范_最终版.txt` | 接口与字段契约、状态机、分层铁律 |
| `换电脑部署说明.md` | 环境安装、环境变量、数据库重建、内网穿透、验证顺序 |

---

## 八、安全与部署提示

- **本仓库不含任何真实密钥**：`application.yml` 所有凭证均为 `${ENV:默认值}` 占位符；`.gitignore` 已排除 `.idea/`、构建产物等易泄密文件。
- 默认管理员密码 `Admin@123`、JWT 默认占位串均为**开发演示用**，公网部署前必须改。
- 微信登录需在公众平台配置 **IP 白名单**（换网络 IP 会变，否则 `code2Session` 报 `40164`）。
- OneNET 推送接口在 `ONENET_PUSH_SECRET` 为空时处于免鉴权状态，仅本机联调可接受。
- 后端 `server.port` 默认 `8080`；若被占用，改 `library-seat/src/main/resources/application.yml` 即可（管理端 `vite.config.js` 的 `BACKEND` 同步调整）。

---

## 九、License

[MIT](./LICENSE) © 代茂源 — 物联网工程技术专业
