# 03 · OneNET 接入指南

> 本文是**设备 ↔ 云平台 ↔ 后端**这段链路的接入说明：控制台怎么配、报文长什么样、
> 后端怎么处理、怎么一步步联调。
>
> 本文替代原来的 `library-admin/IoT云平台集成指南.md`。原文档的架构是**错误**的：
> 它让 Vue 前端直接持有 `device_secret` 并用 MQTT 连物联网平台。按《四端统一最终方案》§53 与
> 《实际编码规范》§34，这条路径被明确禁止：
>
> > Web 想直接调用 OneNET：禁止。前端绝对不能直接拼 OneNET 请求。
>
> **物联网平台凭证只存在于 Spring Boot 后端。** 前端和小程序只和 Spring Boot 说话。

---

## 一、职责边界

```
STM32 ──UART──> ESP8266 ──MQTT──> OneNET ──HTTP推送──> Spring Boot ──> MySQL
                                                          │
                                                          ├──WebSocket──> 小程序
                                                          └──WebSocket──> Web管理端

Web管理端 ──HTTP──> Spring Boot ──OpenAPI──> OneNET ──MQTT──> ESP8266 ──UART──> STM32
```

| 层 | 负责 | 不负责 |
| --- | --- | --- |
| OneNET | 设备连接、MQTT、物模型、消息转发、指令下发 | 预约规则、用户、状态机、权限 |
| Spring Boot | 全部业务逻辑（唯一业务大脑） | —— |
| Web / 小程序 | 显示与操作 | ADC 判断、PIR 判断、状态机、OneNET 通信 |

---

## 二、OneNET 控制台配置

### 2.1 创建产品

协议选择 **MQTT**，数据格式选择 **OneJSON**，接入方式选择**设备直连**。

> 本项目的产品是 **OneNET Studio（企业实例）**，域名 `iot-api.heclouds.com`。
> 旧的标准版 `open.iot.10086.cn` 接口路径与鉴权方式都不同，**不能混用**。
> 判定依据：本项目 `product_id` 是 10 位字母数字混合（`Wh08f3Q71p`），
> 物模型带 `profile.industryId / sceneId / categoryId`，都是 Studio 特征。

### 2.2 物模型（编码规范 §16 最终版本，只保留以下内容）

**只读属性**（设备上报）

| 标识符 | 数据类型 | 说明 |
| --- | --- | --- |
| `pressure_adc` | int | FSR402 压力 ADC 原始值 |
| `pir_state` | bool | HC-SR505 人体红外状态 |
| `alarm_flag` | bool | 设备本地判定的假占座告警 |

> **物模型里没有 `online` 属性。** 设备在线是平台 / 后端的概念，不是物模型属性：
> 平台侧有 `$sys/{pid}/{device-name}/online` 上线通告；后端侧按
> `now - seat_shadow.last_report_at > 120s` 判定离线（见 §五）。
> 设备心跳报文里带的那个 `online` 字段只是上报内容的一部分，
> 后端据此把 `seat_shadow.online` 置 1。
>
> 物模型导出文件 `model-Wh08f3Q71p.json`（仓库根目录）里总共只有
> **5 个属性、2 个事件、2 个服务**，与本表一致；在控制台「导入物模型」里选它
> 即可一键还原，不用手敲。

**可写属性**（云端下发）

| 标识符 | 数据类型 | 说明 |
| --- | --- | --- |
| `seat_display` | enum | OLED 显示的座位业务状态（**0 空闲 / 1 已预约 / 2 使用中 / 3 暂离 / 4 异常**）；强制释放时下发 `display-clear-value`（默认 0） |
| `adc_threshold` | int | 压力判定阈值，管理员在设备管理页修改 |

**事件**

| 标识符 | 输出参数 | 说明 |
| --- | --- | --- |
| `rfid_scan` | `rfid_uid` (string) | 刷卡（共享读卡器或座位终端） |
| `fake_occupy` | `pressure_adc`、`pir_state` | 假占座触发 |

**服务**

| 标识符 | 输入参数 | 说明 |
| --- | --- | --- |
| `buzzer_ctrl` | `duration_ms` (int) | 蜂鸣器测试 |

> `pir_hold_time` 已从物模型删除（方案 §13）。PIR 时间窗口属于 STM32 本地算法参数，不做云端配置。
>
> ✅ 早先 `fake_occupy` 事件输出参数 `pir_state` 的布尔映射与属性相反（`true=0/false=1`），
> **2026-09-29 已在控制台修正为 `true=1/false=0`**，现与属性一致。

### 2.3 创建设备

每个座位终端一个设备，另加一个共享读卡器：

```
SEAT_001 ~ SEAT_0NN
READER_01
```

记录每个设备的：产品 ID（pid）、设备名称（**device_name**）、鉴权 token。

**这些值只进后端环境变量，不进任何前端仓库。**

> 共享读卡器 `READER_01` **不占座位**。它的 `rfid_scan` 上报走到"device_name 没有对应座位"
> 那条 WARN 是**正常现象**，不是 bug。

### 2.4 配置数据推送

在 OneNET 控制台配置 HTTP 推送（数据推送 / 规则转发），把设备属性上报与事件 POST 到后端：

```
POST https://<你的后端域名>/api/onenet/device-data
POST https://<你的后端域名>/api/onenet/rfid-event
```

同一页面上还要确认三件事，缺一个都会静默不通：

| 项 | 期望值 |
| --- | --- |
| 消息加密方式 | **明文** |
| 生效状态开关 | **已打开** |
| 推送鉴权 | 加自定义请求头 `X-Push-Secret: <ONENET_PUSH_SECRET>`（推荐），或 URL 后缀 `?push_secret=<值>` |

本地开发时后端在 `localhost:8080`，公网不可达，需要用内网穿透（cpolar / ngrok / frp）把
OneNET 的推送打到本地。

> ⚠️ **穿透域名变了必须同步改这里的 URL** —— OneNET 在云上，**访问不到 localhost**。
> 这是整条链路里最容易漏的一处：小程序只改 `utils/config.js` 一行，这里不改，
> 设备上报与刷卡签到就全断，而且后端不会报错（因为根本没收到请求）。

---

## 三、上行：设备数据进入后端

### 3.1 报文格式（以实测为准）

**设备属性上报** —— 这是**已经实测跑通**的形态：

```json
{
  "device_name": "SEAT_001",
  "data": {
    "pressure_adc": 1500,
    "pir_state": true,
    "alarm_flag": false,
    "online": true
  }
}
```

> ⚠️ **字段名必须是 `device_name`，不是 `deviceName`、也不是 `device_id`。**
> 写成驼峰的 `deviceName` 会被**静默丢弃**，接口照样返回 200 ——
> 所以**不能拿 200 当通过**，要回查 `seat_shadow` 表确认值真的写进去了。

**刷卡事件**（信封与属性上报同一套，事件标识与输出参数放在 `data` 里）：

```json
{
  "device_name": "READER_01",
  "data": {
    "event": "rfid_scan",
    "rfid_uid": "A1B2C3D4"
  }
}
```

**心跳**：无状态变化时每 60 秒一次，`online: true`，字段同属性上报（可以只带 `online`）。

**假占座事件**：`data` 里带 `event: "fake_occupy"` 与 `pressure_adc` / `pir_state`。

#### 后端兼容的其它形态

`OneNetController` 收的是**原始 `JsonNode`**，不是直接反序列化成 DTO ——
因为 Studio 推送的信封结构和 Postman 联调用的简洁格式不一致，而且这个信封在开发期
一个字段都验证不了。所有形态差异都在 `OneNetServiceImpl.normalize` **一个方法**里抹平，
后面所有业务代码只认归一化后的 `OneNetDataDTO`。

`normalize` 目前兼容：**平铺格式**（不带 `data`）、**Studio 信封**、
**`{"value": x, "time": t}` 包装**、**字符串化的 `"2048"` / `"true"`**、
**数字 `0` / `1` 当布尔**、以及脏值。这六种都有 `OneNetServiceImplTest` 的 16 个用例钉着。

真机联调时状态死活不更新，第一件事是把 `application.yml` 里那行
`com.example.libraryseat.service.impl` 从 `info` **临时改成 `debug`** —— 它会把原始报文
整段打在 DEBUG 上，照着改 `normalize` 就行；**联调完记得改回 `info`**。

### 3.2 处理链路（编码规范 §17）

```
OneNetController  →  OneNetService  →  SeatService / RfidService  →  MySQL  →  WebSocketService
```

`OneNetController` 只接收和转发，**不处理业务**。

`OneNetService.handleDeviceData()` 负责：

1. 校验 `device_name` 是否存在于 `seat` 表（查不到 → 只记 WARN 就返回）
2. 更新 `seat_shadow`：`pressure_adc` / `pir_state` / `alarm_flag` / `online=1` / `last_report_at`
3. 若 `alarm_flag` 由 0 变 1：座位业务状态置 `ALARM(4)`，写一条 `violation`（type=`FAKE_OCCUPY`）
4. 若 `alarm_flag` 由 1 变 0：业务状态从 `ALARM` 恢复（方案 §29）
5. 调 `WebSocketService` 广播 `SEAT_UPDATE` / `ALARM` / `DEVICE_STATUS`

`OneNetService.handleRfidEvent()` 负责：解析 `rfid_uid` → 调 `RfidService.signIn(rfidUid)`。

> **RFID 不选择座位。** RFID 只证明"这个人签到了"，签的是哪个座位由他当前的 RESERVED
> 预约决定（方案 §26）。设备用 `device_name` 找不到座位是正常的。

**"处理不了"和"这次没处理成"要分开**：

- 报文本身有问题（`device_name` 查不到座位、字段缺失、结构不认识）→ 只记 WARN 就返回，**绝不抛**。
  抛出去就是 500，OneNET 会把一条永远处理不了的数据**反复重推**；
- 数据库连不上这类**临时**故障 → 让它正常抛，平台重推一次就好了。

---

## 四、下行：后端控制设备

### 4.1 业务层内部格式（编码规范 §42）

蜂鸣器测试：

```json
{ "service": "buzzer_ctrl", "duration_ms": 1000 }
```

OLED 显示 / 修改阈值：

```json
{ "property": "seat_display",  "value": 0 }
{ "property": "adc_threshold", "value": 2000 }
```

强制释放：**不调用 `force_release` 服务**，实际只下发 `seat_display = 0`
（`onenet.display-clear-value`），与上面第 2 行同属属性下发通道。

这是**业务层内部格式**，由 `OneNetDownlinkService` 翻译成 OneNET OpenAPI 请求。
前端只调后端的 REST 接口，永远看不到这一层。

### 4.2 触发入口

| 管理端操作 | REST 接口 | 下行内容 |
| --- | --- | --- |
| 强制释放 | `POST /api/admin/seats/{id}/force-release` | `seat_display: 0`（不调用 `force_release` 服务） |
| 消除告警 | `POST /api/admin/seats/{id}/clear-alarm` | `seat_shadow.alarm_flag=0`，必要时同步 OLED / 蜂鸣器 |
| 修改 ADC 阈值 | `POST /api/admin/devices/{id}/config` | `property: adc_threshold` |
| 测试蜂鸣器 | `POST /api/admin/devices/{id}/buzzer` | `service: buzzer_ctrl` |

每个操作都要写 `operation_log`（方案 §36）。

### 4.3 OpenAPI 调用

用 OkHttp 封装，凭证与**每一个路径**都在 `application.yml` 的 `onenet:` 段里：

```yaml
onenet:
  base-url: ${ONENET_BASE_URL:https://iot-api.heclouds.com}
  product-id: ${ONENET_PRODUCT_ID:Wh08f3Q71p}
  api-key: ${ONENET_API_KEY:}
  token-version: 2018-10-31
  token-method: sha1
  token-res-prefix: products/
  token-expire-seconds: 3600
  property-set-path: /thingmodel/set-device-property
  service-invoke-path: /thingmodel/call-service
```

> 开发期**无法连通 `iot-api.heclouds.com` 实测**，Studio 的路径也随版本调整过。
> 所以全部留成配置：真机联调若返回 `404 / 406`，**改这一行即可，不用改 Java、不用重新编译**。
>
> ⚠️ 已实测确认的两条铁律（2026-11-05 逐条验证过，别再凭文档猜）：
> 1. **path 里不能带 `/iot-api` 前缀**，否则网关回 `406 HTTP url namespace err`。
> 2. **authorization 头里的参数名是 `sign`，不是 `signature`**。同一个 key、同样的
>    res/version/算法，只把这一个词写错，平台就回
>    `code=10403 authentication failed: invalid authorization` —— 它不会告诉你
>    是字段名错，极容易被误判成"key 失效"。
> 3. `api-key` 必须是**产品级 AccessKey**（44 字符 base64，签名前要 base64 解码）。
>    账号级那种 32 位十六进制串签名永远不过，同样报 10403。
> 4. `params` 是**必填**字段，服务没有入参也要发 `{}`，否则回 `10001 ... 'Params' required`。

下发是 `@Async`，**失败只记日志，不回滚业务状态**。理由是设备可能离线，
等它的同步返回会把管理端的请求一起挂住，而且"阈值没下发成功"不该让
"管理员改了阈值"这个业务动作失败。

> **不做** `pending_display` 持久补偿队列（方案 §41 第 18 条明确删除）。
> 下发失败即失败，设备下次心跳会以云端最新状态为准。

---

## 五、设备在线判定

在线状态**由后端计算**，前端不自己判断（方案 §14）。

`DeviceOfflineTask` 每 60 秒执行：

```
now - seat_shadow.last_report_at > 120s  →  online = 0  →  广播 DEVICE_STATUS
```

120 秒 = 心跳周期 60 秒 × 2，允许丢一个包（编码规范 §25）。

离线巡检用 `markOfflineIfStale` 做**条件更新**
（`WHERE id=? AND online=1 AND last_report_at<?`），而不是先查后无条件 `updateById` ——
查和改之间设备可能刚好上报了一条，无条件更新会把一台活着的设备标成离线。

---

## 六、WebSocket 广播格式（编码规范 §18）

地址 `/ws/seats?token=xxx`，小程序与 Web 管理端**共用同一套消息格式**。

```json
{ "type": "SEAT_UPDATE",   "seat_id": 1, "status": 2, "alarm": false, "online": true, "timestamp": 1750000000 }
{ "type": "DEVICE_STATUS", "seat_id": 1, "online": false, "timestamp": 1750000000 }
{ "type": "ALARM",         "seat_id": 1, "alarm": true, "timestamp": 1750000000 }
```

`WebSocketService` 必须**复用**配置了 `SNAKE_CASE` 的 `ObjectMapper`，
否则会发出 `seatId`，前端两个端同时解析失败。

`WsMessage` 上有 `@JsonInclude(NON_NULL)`：**没赋值的字段不会出现在 JSON 里**，
这是为接收端的增量合并服务的，删掉会把它打坏。

> **没有 `RESERVATION_UPDATE` 这一种。** 编码规范 §18.4 里列过它，但后端**刻意不发**：
> 订单的每一次跃迁必然伴随所在座位的业务状态变化，`SEAT_UPDATE` 的字段是它的严格超集，
> 两种都发只会让客户端对同一个座位合并两次同样的值。
> 前端想实时刷新订单，就按 `seat_id` 对上自己那几单所在的座位。

---

## 七、联调顺序（编码规范 §46）

1. `GET /api/seats` —— Postman 能拿到座位
2. `POST /api/reservations` —— 能预约
3. leave / return / release
4. **模拟 RFID**：`POST /api/onenet/rfid-event`，手工构造刷卡报文
5. **模拟设备**：`POST /api/onenet/device-data`，手工构造属性上报
6. 浏览器 / 小程序连 `/ws/seats` 看广播
7. 接真实 STM32 + ESP8266
8. 小程序
9. Web 管理端

第 4、5 步**不需要任何硬件，也不需要 OneNET 推送配好** —— 直接用 Postman 打后端接口即可，
这是把设备端和云端解耦调试的关键。用 Postman 打时记得带上推送密钥
（请求头 `X-Push-Secret` 或 URL 参数 `push_secret`），否则会被 401 拒掉。

---

## 八、明确不做

- 前端持有任何 OneNET / 华为云 / 阿里云凭证
- 前端 MQTT 直连物联网平台
- 自建 EMQX Broker（方案 §41 第 20 条：不要双平台并行）
- OTA、固件升级、远程重启、设备分组、批量配置
- `pir_hold_time` 云端配置
- `pending_display` 持久补偿队列
