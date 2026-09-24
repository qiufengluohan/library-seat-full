# 智慧图书馆座位管理后台（Web 管理端）

基于 Vue 3 + Element Plus 的图书馆座位管理 Web 后台，是「设备端 → OneNET → Spring Boot → 管理端 / 小程序端」链路里的**管理端**。

本端**只和 Spring Boot（`library-seat/`）通信**，不直连任何物联网平台。

## 技术栈

- Vue 3（Composition API，`<script setup>`）
- Element Plus + @element-plus/icons-vue
- Pinia / Vue Router 4 / Axios
- ECharts 5（大屏图表）
- Day.js（时间格式化）
- Vite 5 + Sass

## 职责边界（务必先读）

依据《四端统一最终方案》§41 与《实际编码规范》§53：

- **Web 端禁止直连 OneNET / MQTT。** 前端不持有任何设备密钥，`package.json` 里没有 `mqtt`、`crypto-js`。
- 所有设备控制（改 ADC 阈值、测试蜂鸣器、强制释放、消除告警）都调 `library-seat` 的 REST 接口，由后端的 `OneNetService` 翻译成 OneNET 下发报文。
- **业务状态一律由后端给出。** 前端只做「状态码 → 展示」的映射（见 `src/constants/status.js`），绝不根据 `pressure_adc` / `pir_state` 反推座位状态。
- 实时性来自 WebSocket `/ws/seats`，与小程序端共用同一套消息格式。

## 数据契约：snake_case

《实际编码规范》§4 规定 **JSON 字段一律 snake_case**（`seat_id`、`seat_code`、`reserve_time`、`pressure_adc`…）。

这依赖 `library-seat` 的 `application.yml` 里：

```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
```

**前端所有字段名都按 snake_case 书写。如果后端关掉这个配置，管理端和小程序端会同时白屏。** 改后端配置前请先看 `library-seat/README.md` 的「不要改的配置」一节。

## 页面

| 路由 | 页面 | 说明 |
|------|------|------|
| `/login` | 登录 | 调 `POST /api/admin/login`，密码校验在后端（BCrypt） |
| `/dashboard` | 实时大屏 | 8 张座位/设备卡片 + 4 张今日指标 + 2 张 ECharts 图 |
| `/seats` | 座位管理 | 网格地图、颜色图例、离线角标、详情、消除告警、强制释放 |
| `/devices` | 设备管理 | 在线状态、最后上报、压力/红外/告警，改 ADC 阈值、测试蜂鸣器 |
| `/rfid` | RFID 管理 | 卡与学生的绑定 / 解绑 |
| `/violations` | 违规与统计 | 三个标签页：违规记录 / 学习统计 / 操作日志 |

方案 §41 要求删除的页面（黑名单、拖拽布局、复杂个人中心、动态系统设置、报表导出）**已连同路由一起删除**，不要加回来。

## 状态与颜色

唯一定义在 `src/constants/status.js`：

| 值 | 含义 | 颜色 |
|----|------|------|
| 0 | FREE 空闲 | 绿 |
| 1 | RESERVED 已预约 | 黄 |
| 2 | USING 使用中 | 红 |
| 3 | AWAY 暂离 | 灰 |
| 4 | ALARM 异常告警 | 红色闪烁 |

设备离线**不是**业务状态：`online=false` 只渲染一个叠加的「离线」角标，不覆盖业务状态颜色。

## 目录结构

```
library-admin/
├── src/
│   ├── api/                # 15 个管理端 REST 接口，按资源分文件
│   │   ├── auth.js  admin.js  seat.js  device.js  rfid.js  statistics.js
│   ├── components/
│   │   ├── Layout.vue      # 主布局，全局唯一 WebSocket 在这里建立
│   │   ├── StatCard.vue    # 统计卡片
│   │   ├── SeatCard.vue    # 单个座位格子（含离线角标、告警闪烁）
│   │   └── SeatGrid.vue    # 按区域分组的座位网格
│   ├── constants/
│   │   └── status.js       # 状态/颜色/标签的唯一来源
│   ├── router/index.js     # 6 个页面 + 登录守卫
│   ├── stores/
│   │   ├── auth.js         # token 与管理员信息
│   │   └── seat.js         # 全馆座位状态的唯一持有者（快照 + WS 增量）
│   ├── utils/
│   │   ├── request.js      # axios 实例、Result 解包、401 跳登录
│   │   ├── websocket.js    # /ws/seats 客户端，断线 3 秒重连
│   │   └── format.js       # 时间/时长格式化
│   └── views/              # Login / Dashboard / SeatManage / DeviceManage /
│                           # RfidManage / ViolationManage
├── .env.development        # 只有地址，没有密钥
├── .env.production
└── vite.config.js          # /api 与 /ws 代理到 localhost:8080
```

## 运行

前置条件：MySQL 已建库建表、`library-seat` 已启动（见 `../library-seat/README.md`）。

```bash
npm install
npm run dev        # http://localhost:3000，/api 与 /ws 自动代理到 8080
npm run build
```

**没有后端时**：登录会失败并提示「无法连接后端服务，请确认 library-seat 已启动」；已登录状态下各页面显示空数据和同一条错误横幅，WebSocket 标签显示「连接断开」并每 3 秒重连。这是预期行为——本项目不提供 mock 数据层。

管理员账号在 `library-seat` 的 `admin_user` 表里，密码为 BCrypt 哈希，需要按 `../library-seat/README.md` 的说明自行生成后插入。

## 相关文档

- `../library-seat/README.md` — 后端骨架、包结构清单、启动步骤
- `../library-seat/docs/OneNET接入指南.md` — OneNET 控制台配置、上行/下行报文、联调顺序
- `../智慧图书馆座位智能管理系统_实际编码规范_最终版.txt` — 接口与字段契约
- `../智慧图书馆座位智能管理系统_四端统一最终方案.txt` — 四端职责与页面清单

## 开发者

代茂源 - 物联网工程技术专业

## License

MIT
