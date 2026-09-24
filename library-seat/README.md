# library-seat — 业务云端

智慧图书馆座位智能管理系统的 Spring Boot 后端。按《实际编码规范（最终统一开发版）》§7 建立。

**这是整个系统唯一的业务大脑。** 设备端只感知和执行，前端只显示和操作，所有业务判断都在这里。

---

## 当前状态

**Java 业务代码已全部写完，可以编译、可以启动、测试全绿。**

| 项 | 数量 |
|---|---|
| 主代码 `.java` | 102 |
| 测试 `.java` | 6（55 个用例） |
| HTTP 接口 | 31 |
| Controller | 10 |
| Service 接口 + 实现 | 13 + 13 |
| Entity + Mapper | 9 + 9 |

`mvn compile` 与 `mvn test` 均通过；应用能在 JDK 27 上正常启动（Spring Boot 3.2.5）。

**已经真机跑过的**：建库建表 + 种子数据、31 个接口的鉴权分支、全部 SQL 查询、WebSocket 广播、
两条超时规则（预约超时 / 暂离超时）、强制释放和学习时长。
**唯一还没跑过的**是 OneNET 下行（要真机 + 有效凭证），见文末《验证状态》。

---

## 目录结构

```
com.example.libraryseat
├── common/       Result / ErrorCode / BusinessException / GlobalExceptionHandler
├── enums/        SeatStatus / ReservationStatus / ViolationType
├── entity/       User AdminUser Seat Reservation RfidUser SeatShadow
│                 StudyRecord Violation OperationLog
├── mapper/       与 entity 一一对应的 9 个 Mapper
├── dto/          入参（含校验注解）
├── vo/           出参（永远不含密码、openid 之外的敏感字段）
├── service/      13 个接口 + service/impl/ 13 个实现
├── config/       MyBatisPlusConfig WebMvcConfig WebSocketConfig JacksonConfig AppConfig
│                 OneNetProperties WechatProperties SeatProperties
├── security/     JwtInterceptor AuthContext
├── websocket/    SeatWebSocketHandler TokenHandshakeInterceptor WsMessage
├── task/         ReservationTimeoutTask DeviceOfflineTask
├── controller/   10 个
└── util/         JwtUtil JsonUtil TimeUtil OneNetTokenUtil
```

Service 有 13 个而不是规范 §7 写的 12 个：多出来的 `OneNetDownlinkService` 是从 `OneNetService` 里拆出来的。
拆的原因是 Bean 循环依赖 —— `OneNetService → RfidService → ReservationService → DeviceService → OneNetService`。
上行（解析推送）和下行（调 OneNET API）本来就是两件事，拆开后依赖变成单向。

---

## 启动步骤

### 1. 建库建表

```bash
mysql -u root -p < src/main/resources/db/01_schema.sql
```

### 2. 种子数据

```bash
mysql -u root -p < src/main/resources/db/02_seed.sql
```

种进去的只有两条：管理员 `admin` / `Admin@123`，和一个真实设备座位 `A4-203 → SEAT_001`。

其余表**刻意不种子**：`user` 由微信登录自动建，`rfid_user` 要真实卡号（在管理端界面绑），
`seat_shadow` 由设备第一次上报时建（预先种进去会立刻被离线巡检标成离线），
四张业务流水表由真实操作产生 —— 答辩时真数据比时间戳对不上的假数据有说服力。

> ⚠️ `Admin@123` 是公开写在仓库里的默认密码，只用于开发演示。
> 公网部署前必须改，改法见 `02_seed.sql` 里的注释。

### 3. 配置环境变量

**不要把凭证写进 yml 提交。** 全部走环境变量：

```bash
MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_USERNAME=root
MYSQL_PASSWORD=你的密码
JWT_SECRET=至少32字节的随机串
ONENET_API_KEY=OneNET 控制台上的产品级 api-key
ONENET_PUSH_SECRET=自定义的一串随机值，同时填到 OneNET 推送 URL 上
WECHAT_APP_ID=你的小程序 AppID
WECHAT_APP_SECRET=你的小程序 AppSecret
```

Windows 上具体怎么给（PowerShell 里 `$env:` 只对当前窗口有效，换了窗口要重设）：

```powershell
# PowerShell
$env:MYSQL_PASSWORD      = "你的密码"
$env:WECHAT_APP_SECRET   = "你的小程序 AppSecret"
mvn spring-boot:run
```

```bash
# Git Bash
export MYSQL_PASSWORD="你的密码"
export WECHAT_APP_SECRET="你的小程序 AppSecret"
mvn spring-boot:run
```

IDEA 里跑：Run/Debug Configurations → Environment variables 一栏填
`MYSQL_PASSWORD=xxx;WECHAT_APP_SECRET=xxx`。
用 `setx` 写进注册表的值，**已经开着的 IDEA / 终端读不到**，得重启那个程序。

缺哪个就会在启动日志里看到对应的 WARN（OneNET 下行、`/api/onenet/**` 鉴权、微信登录都会自检）；
微信登录失败时小程序只看到一句"未配置"，看一眼启动日志更快。

`ONENET_PUSH_SECRET` 有两种带给后端的写法，任选其一：

- 推送请求头 `X-Push-Secret: <值>`（推荐）
- 推送 URL 上拼 `?push_secret=<值>`

**留空也能启动**，但 `/api/onenet/**` 就处于免鉴权状态，任何人都能伪造一条"设备上报"
把座位状态刷成任意值。留空时每次请求都会打一条 WARN，不会静默。

### 4. 跑起来

```bash
mvn spring-boot:run
```

---

## 测试

```bash
mvn test
```

55 个用例，覆盖六块最容易出错、且**开发期完全无法真机验证**的逻辑：

| 测试类 | 用例 | 盯的是什么 |
|---|---|---|
| `OneNetTokenUtilTest` | 7 | token 签名。含一个用 node 独立算出的黄金向量，以及"改动四段中任意一段签名都变"的断言 |
| `OneNetServiceImplTest` | 16 | 上行报文归一化。平铺格式、Studio 信封、`{"value":x,"time":t}` 包装、字符串化的 `"2048"/"true"`、数字 0/1 布尔、脏值 |
| `ReservationStatusTest` | 8 | 状态过滤。`FINISHED` 要展开成三个终态；未知值必须返回**空集**而不是"不过滤" |
| `SeatStatusTest` | 6 | 状态码 0-4（改 4 就改坏了小程序的红闪）；`fromReservation` 永远不产出 ALARM |
| `TimeUtilTest` | 11 | 秒/毫秒换算、时区、时钟回拨不产出负数、`minutesBetween` 的截断行为、**小数秒会让 REST 与 `DATETIME(0)` 差 1 秒** |
| `ViolationServiceImplTest` | 7 | `violation.user_id` 是唯一可空外键，空表 + null 键同时出现时不能抛 NPE；类型筛选非法值要回空页而不是全表 |

> 本地 Maven 仓库缺 surefire 插件，**第一次跑 `mvn test` / `mvn package` 必须联网**，
> 让它从 alimaven 下载。加 `-o` 会直接失败。

---

## ⚠️ 不要改的配置

### Jackson 的 snake_case

```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
```

编码规范 §4 规定 JSON 统一 snake_case。Spring Boot 默认输出 camelCase，
一旦改掉或漏配，`device_id` 会变成 `deviceId`，**小程序和 Web 管理端同时解析失败**，
OneNET 推送进来的数据也无法映射到 DTO。

同理，`WebSocketService` 必须复用这个 ObjectMapper 序列化广播消息。

注意 **SNAKE_CASE 管不到 `@RequestParam` 的参数名**，所以查询参数要显式写：
`@RequestParam(name = "seat_id")`。漏了就会变成 `seatId`，前端传的值收不到。

### HTTP 状态码约定

`ErrorCode.httpStatus()` 里定死的规则：**只有 401 和 500 用真实的 HTTP 状态码**，
400 / 403 / 404 / 409 一律返回 **HTTP 200 + 响应体里的 `code`**。

```json
HTTP/1.1 200
{"code":403,"message":"需要管理员权限","data":null}
```

管理端的 `request.js` 就是按这个约定写的 —— `error.silent = body.code === 409`
这行代码在 axios 的**成功**拦截器里。改成"用真实 HTTP 状态码"会让 409 冲突提示
从静默变成弹错误框，业务判断（预约冲突、重复绑卡）全部走错分支。

---

## 分层铁律（编码规范 §18）

```
Controller → Service → 业务处理 → MySQL → WebSocket
```

禁止 Controller 直接调 OneNET、直接改数据库、直接发 WebSocket。
禁止把预约状态机写在 Controller 里。

**前端绝对不能直接拼 OneNET 请求**（§42 / §53）。管理端点"下发阈值"时，
请求打到 `/api/admin/devices/{id}/config`，由 `DeviceService` 翻译成 OneNET 属性下发。
api-key 永不出后端进程。

---

## 接口清单（31 个）

| 路径 | 方法 | 说明 |
|---|---|---|
| `/api/auth/wechat-login` | POST | 微信登录，**免 JWT** |
| `/api/users/me` | GET / PUT | 我的资料 |
| `/api/seats` | GET | 座位列表（数组，不分页） |
| `/api/seats/{seatId}` | GET | 座位详情 |
| `/api/reservations` | GET / POST | 我的预约列表 / 发起预约 |
| `/api/reservations/current` | GET | 当前进行中的预约，没有则 `data:null`（**不是 404**） |
| `/api/reservations/{id}` | GET | 预约详情 |
| `/api/reservations/{id}/cancel\|leave\|return\|release` | POST | 取消 / 暂离 / 回座 / 离座 |
| `/api/statistics/me` | GET | 我的学习统计 |
| `/api/admin/login` | POST | 管理员登录，**免 JWT** |
| `/api/admin/dashboard` | GET | 首页看板 |
| `/api/admin/seats` | GET | 座位列表（管理端，带学生信息） |
| `/api/admin/seats/{id}` | GET | 座位详情 |
| `/api/admin/seats/{id}/clear-alarm` | POST | 清除告警 |
| `/api/admin/seats/{id}/force-release` | POST | 强制释放 |
| `/api/admin/devices` | GET | 设备列表（读影子表，不实时问 OneNET） |
| `/api/admin/devices/{deviceId}/config` | POST | 下发 ADC 阈值 |
| `/api/admin/devices/{deviceId}/buzzer` | POST | 蜂鸣器测试 |
| `/api/admin/rfid-binds` | GET / POST | 绑卡列表 / 绑卡 |
| `/api/admin/rfid-binds/{uid}` | DELETE | 解绑 |
| `/api/admin/statistics` | GET | 全馆统计 |
| `/api/admin/violations` | GET | 违规记录（分页，只读） |
| `/api/admin/logs` | GET | 操作日志（分页） |
| `/api/onenet/device-data` | POST | OneNET 上行推送，**走推送密钥不走 JWT** |
| `/api/onenet/rfid-event` | POST | 同上 |

两个易错点：

- `{deviceId}` 是 OneNET 的 **device_name 字符串**（`SEAT_001`），不是数据库主键，
  也不是控制台里那串数字 ID。`{uid}` 同理是卡号字符串。
- **没有 `/sign` 接口。** 签到只能刷卡（§14.8），小程序上也没有签到按钮。

---

## 鉴权

`security/JwtInterceptor` 是唯一的鉴权入口，三条分支：

| 路径 | 规则 |
|---|---|
| `/api/auth/wechat-login`、`/api/admin/login` | 放行（在 `WebMvcConfig` 里 exclude） |
| `/api/onenet/**` | 共享密钥，`X-Push-Secret` 头或 `push_secret` 参数，用 `MessageDigest.isEqual` 常量时间比较 |
| 其余 `/api/**` | JWT；`/api/admin/**` 额外要求 `role=ADMIN`，否则 403 |

只有 STUDENT 和 ADMIN 两种角色，不做 RBAC（§36 / §37）。
管理员 token 也能访问学生接口 —— 它读到的座位列表和学生看到的完全一样，不涉及越权。

---

## 定时任务

两个任务都是 `@Scheduled(fixedDelay = 60_000)`。Spring 默认调度器只有一个线程，
所以它们天然串行，不会互相打断。

| 任务 | 规则 |
|---|---|
| `ReservationTimeoutTask` | RESERVED 且 `reserve_expire_at < now` → TIMEOUT → 座位转 FREE；AWAY 且 `leave_expire_at < now` → COMPLETED → FREE |
| `DeviceOfflineTask` | `now - last_report_at > 120s` → `online=false` + 广播 |

用轮询而不是 Redis 延迟队列（§24、§49）。120 秒是"允许丢一次 60 秒心跳"的余量。

**离线不是座位状态**（§5.3 / §39）。`SeatStatus` 只有 0-4 五个值，离线是影子表上的
一个附加标记，前端渲染成角标。所以离线巡检只改 `seat_shadow`，**绝不碰 `seat` 表**。

离线巡检用 `markOfflineIfStale` 做条件更新（`WHERE id=? AND online=1 AND last_report_at<?`）
而不是先查后无条件 `updateById`：查和改之间设备可能刚好上报了一条，无条件更新会
把一台活着的设备标成离线，最长要等下一轮才恢复。

两个任务都**不加 try/catch** —— Spring 的调度器会捕获异常、记 ERROR、然后照常安排下一次。
实测：数据库连不上时每 60 秒稳定失败一次，但巡检从不停摆。

---

## OneNET

平台是 **OneNET Studio**（`iot-api.heclouds.com`），不是旧的 `open.iot.10086.cn`。
设备按 `product_id` + `device_name` 寻址。

### 上行

只做 HTTP 推送（不做 MQTT 订阅）。`OneNetController` 收的是**原始 `JsonNode`**，
不是直接反序列化成 DTO —— Studio 推送的信封结构和 Postman 联调用的简洁格式不一致，
而且这个信封在开发期一个字段都验证不了。所有形态差异都在
`OneNetServiceImpl.normalize` 一个方法里抹平，后面所有业务代码只认归一化后的 `OneNetDataDTO`。

真机联调时状态死活不更新，第一件事是打开 `application.yml` 里注释掉的那行
`com.example.libraryseat.service.impl: debug`，它会把原始报文整段打出来，照着改 `normalize` 就行。

"处理不了"和"这次没处理成"要分开：报文本身有问题（device_id 查不到座位、字段缺失、
结构不认识）时只记 WARN 就返回，**绝不抛** —— 抛出去就是 500，OneNET 会把一条
永远处理不了的数据反复重推；但数据库连不上这类临时故障要让它正常抛，平台重推一次就好了。

共享读卡器 `READER_01` 不占座位，它的 `rfid_scan` 上报走到"device_id 没有对应座位"
那条 WARN 是**正常现象**，不是 bug。

### 下行

`@Async` 且**失败不影响业务**：只记日志，不回滚状态，不让接口报错。
理由是设备可能离线，等它的同步返回会把管理端的请求一起挂住，
而且"阈值没下发成功"不该让"管理员改了阈值"这个业务动作失败。

所以 `/config` 和 `/buzzer` 返回 200 **只代表指令已经交给平台**，
不代表设备收到了。设备有没有真的收到，看影子表里下一次上报的值。

base-url 和**每一个路径**都在 `application.yml` 里（`onenet.property-set-path`、
`onenet.service-invoke-path`）。Studio 的 API 路径在开发期无法验证，
万一写错，改一行配置就行，不用改代码、不用重新编译。

### token

`util/OneNetTokenUtil`。待签串是四段用 `\n` 连接的**固定顺序** `et\nmethod\nres\nversion`，
顺序记错平台一律返回 401 且不告诉你哪段错了。
HMAC 的密钥不是 api-key 原文，而是它 **base64 解码后的字节** ——
漏掉这一步签名永远不对，而且看起来"格式是对的"，极难排查。

这两点都有测试盯着，含一个用 node 独立算出来的黄金向量。

### 物模型

产品 ID `Wh08f3Q71p`。属性 `adc_threshold`(rw) `alarm_flag`(r) `pir_state`(r)
`pressure_adc`(r) `seat_display`(rw)；事件 `fake_occupy` `rfid_scan`；
服务 `buzzer_ctrl`(duration_ms) `force_release`。

> ⚠️ 物模型里 `fake_occupy` 事件的 `pir_state` **输出参数**把 `true` 映射成了 `"0"`、
> `false` 映射成 `"1"`，和 `pir_state` **属性**的定义正好相反。
> 目前无害（后端读的是属性不是事件输出），但建议在控制台改掉，免得以后自己看糊涂。

---

## 日志里绝不能出现的东西

这些都是"打出来就等于泄露凭证或权限"的：

| 内容 | 为什么 | 在哪防的 |
|---|---|---|
| OneNET `authorization` 头 | 一个有效 token 等于对所有设备的 api-key 使用权 | `OneNetDownlinkServiceImpl.post` |
| 微信 code2Session 的完整 URL | URL 上带着 appsecret | `UserServiceImpl.exchangeOpenid` |
| `session_key` / `openid` | openid 能直接反查出是哪个学生，属个人信息 | 登录成功只记 `userId` |
| 推送密钥的值 | 等同于伪造设备数据的权限 | `JwtInterceptor.checkPushSecret`，只记"有没有带" |
| `AdminUser.password` | — | 任何 VO 都不含这个字段；登录失败统一回"用户名或密码错误"，不区分用户不存在和密码错 |

---

## 验证状态

### 已实测通过（真 MySQL 8.0.44 + 真 JVM 起服务）

按"建库 → 种子 → 管理员登录 → dashboard → 座位/设备列表 → 绑卡 → 预约 → 刷卡签到 →
暂离/回来 → 释放 → 强制释放"整条链路跑通，逐项确认过：

- 31 个映射全部注册，Spring Boot 3.2.5 在 **JDK 27** 上 3.8 秒启动，无 Bean 循环依赖
- 鉴权分支全对：无 token / 垃圾 token / 篡改 token → 真实 HTTP 401；
  学生 token 打 `/api/admin/**` → HTTP 200 + `{"code":403}`；
  推送密钥错误 → 真实 HTTP 401；不存在的 `/api/nope` → `{"code":404}`；
  `/api/seats/abc` → `{"code":400}`
- 分页、筛选、排序类 SQL（座位列表、预约列表的 `FINISHED` 展开、违规按类型筛选、
  dashboard 的今日聚合、statistics 的学习时长汇总）都能出正确结果
- **WebSocket 广播**：设备上报 → 座位状态变更 → `/ws/seats` 客户端实时收到，
  消息 `type` 与 `library-admin/src/constants/status.js` 一一对上。
  三种消息都实测收到过：`DEVICE_STATUS`（设备离线→在线）、`SEAT_UPDATE`（status 0↔4）、
  `ALARM`（alarm 翻转）。小程序端那一半用 Node 桩住 `wx.connectSocket` 直连本机 8080 验的
  （`miniprogram/utils/ws.js` 收得到、`seat_id` 归一化成驼峰、取消订阅后不再回调、
  `close()` 后不重连、坏 token 握手被 403 拒掉）
- **两条超时规则**：把 `reserve_expire_at` / `leave_expire_at` 推到过去，
  60 秒内分别被扫成 TIMEOUT 和 COMPLETED，座位同步归位
- 绑定冲突返回 `{"code":409}`（HTTP 仍是 200，管理端才能把它当正常反馈）
- 登录失败时"用户不存在"和"密码错误"应答完全一致，不泄露账号是否存在
- 微信 code2Session **真的发出去了**，拿到微信真实应答 `errcode=40164`，
  说明出站 HTTPS、应答解析、错误映射三样都通

> 📌 `40164` 要在微信公众平台 → 开发管理 → 开发设置 → **IP 白名单**里
> 加上服务器的公网出口 IP，否则微信登录永远是 401。本机联调时加的是家里的宽带出口 IP。

**真机跑出来、单测没抓到的三个缺陷**（都已在代码里修掉，值得记一笔的是它们只在有库时才现形）：

| 现象 | 根因 | 现在挡在哪 |
|---|---|---|
| `/api/admin/violations` → 500 | `Map.of().get(null)` 抛 NPE，而 `violation.user_id` 是唯一可空外键 | `ViolationServiceImpl.loadStudentNames` 用 `Collections.emptyMap()`；`ViolationServiceImplTest` 钉住 |
| 校验提示回 `seatId 不能为空` | 校验注解挂在 Java 字段上，回给发 `seat_id` 的客户端一个 camelCase 名字 | `GlobalExceptionHandler.wireName()` 过一遍**实际生效**的命名策略 |
| `release_time` 比 `reserve_time` 早 1 秒 | MySQL 存进 `DATETIME(0)` 四舍五入，Jackson 格式化截断，同一个内存值两个结果 | `TimeUtil.now()` / `fromEpoch()` 截到整秒；`TimeUtilTest` 钉住 |

### 仍未验证

1. **OneNET 下行**。属性设置、服务调用的 URL 和请求体是按 Studio 文档写的，
   没拿真机验证过。所以 `onenet.*-path` 全部走 `application.yml`，
   路径不对时改一行配置即可；下行是 `@Async` 且失败只记日志，不会回滚业务状态。
   自测方式：管理端点一次"测试蜂鸣器"，看设备是否真响；返回 200 但没反应就先查路径。
2. **OneNET 推送的应答格式**。上行是按"平台收到 200 就认为成功"来假设的，
   真机第一次推送时留意应用日志里有没有解析告警。
3. **三个凭证还没配上**：`ONENET_API_KEY`、`WECHAT_APP_SECRET`、`ONENET_PUSH_SECRET`。
   前两个不配则下行和微信登录不可用，第三个不配则推送接口不设防（仅本机联调可以）。
4. **物模型 `fake_occupy` 事件里的 `pir_state` 方向**。设备端"有人=true"与
   假占座判定的对应关系要在真机上确认一次，判反了就是永远不报假占座或一直报。

---

## 明确不做（编码规范 §49）

FreeRTOS、OTA、固件升级、多管理员、超级管理员、RBAC、Redis 分布式锁、Redis 延迟队列、
Redis 座位缓存、自建 EMQX、Kafka、RabbitMQ、设备分组、批量设备配置、远程重启、
Excel/PDF 导出、用户画像、复杂热力图、复杂推荐、积分、信用分、黑名单等级、
复杂消息中心、指数退避、`pending_display` 持久队列、`pir_hold_time` 云端配置、
多套物联网平台并行、复杂历史设备缓存。

Redis 与 EMQX 在开题报告里提过，最终不部署。论文中说明：
> 系统在原方案中考虑 Redis 用于超时任务与缓存，但结合毕业设计规模，最终采用
> Spring Boot 定时任务实现，降低系统复杂度。
