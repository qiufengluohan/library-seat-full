# OneNET 接入指南（业务云端）

本文档替代原 `library-admin/IoT云平台集成指南.md`。

原文档的架构是**错误**的：它让 Vue 前端直接持有 device_secret 并用 MQTT 连物联网平台。
按《四端统一最终方案》§53 与《实际编码规范》§34，这条路径被明确禁止：

> Web 想直接调用 OneNET：禁止。
> 前端绝对不能直接拼 OneNET 请求。

**物联网平台凭证只存在于 Spring Boot 后端。** 前端和小程序只和 Spring Boot 说话。

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
|---|---|---|
| OneNET | 设备连接、MQTT、物模型、消息转发、指令下发 | 预约规则、用户、状态机、权限 |
| Spring Boot | 全部业务逻辑（唯一业务大脑） | — |
| Web / 小程序 | 显示与操作 | ADC 判断、PIR 判断、状态机、OneNET 通信 |

---

## 二、OneNET 控制台配置

### 2.1 创建产品

协议选择 **MQTT**，数据格式选择 **OneJSON**，接入方式选择**设备直连**。

### 2.2 物模型（编码规范 §16 最终版本，只保留以下内容）

**只读属性**（设备上报）

| 标识符 | 数据类型 | 说明 |
|---|---|---|
| `pressure_adc` | int | FSR402 压力 ADC 原始值 |
| `pir_state` | bool | HC-SR501 人体红外状态 |
| `alarm_flag` | bool | 设备本地判定的假占座告警 |

**可写属性**（云端下发）

| 标识符 | 数据类型 | 说明 |
|---|---|---|
| `seat_display` | int | OLED 显示的座位业务状态（0-4） |
| `adc_threshold` | int | 压力阈值，管理员在设备管理页修改 |

**事件**

| 标识符 | 输出参数 | 说明 |
|---|---|---|
| `rfid_scan` | `rfid_uid` (string) | 共享读卡器刷卡 |
| `fake_occupy` | `pressure_adc`, `pir_state` | 假占座触发 |

**服务**

| 标识符 | 输入参数 | 说明 |
|---|---|---|
| `force_release` | 无 | 管理员强制释放 |
| `buzzer_ctrl` | `duration_ms` (int) | 蜂鸣器测试 |

> `pir_hold_time` 已从物模型删除（方案 §13）。PIR 时间窗口属于 STM32 本地算法参数，不做云端配置。

### 2.3 创建设备

每个座位终端一个设备，另加共享读卡器：

```
SEAT_001 ~ SEAT_0NN
READER_01
```

记录每个设备的：产品 ID（pid）、设备名称（device_name）、鉴权 token。

**这些值只进后端环境变量，不进任何前端仓库。**

### 2.4 配置数据推送

在 OneNET 控制台配置 HTTP 推送（规则引擎 / 数据转发），把设备属性上报与事件 POST 到后端：

```
POST https://<你的后端域名>/api/onenet/device-data
POST https://<你的后端域名>/api/onenet/rfid-event
```

本地开发时后端在 `localhost:8080`，公网不可达，需要用内网穿透（ngrok / cpolar / frp）把
OneNET 的推送打到本地。这一步在联调阶段最容易卡住，建议提前准备好。

---

## 三、上行：设备数据进入后端

### 3.1 统一 JSON 格式（编码规范 §15）

设备状态上报：

```json
{
  "device_id": "SEAT_001",
  "seat_id": "1",
  "pressure_adc": 2450,
  "pir_state": true,
  "alarm_flag": false,
  "timestamp": 1750000000
}
```

RFID 事件：

```json
{
  "device_id": "READER_01",
  "event": "rfid_scan",
  "rfid_uid": "A1B2C3D4",
  "timestamp": 1750000000
}
```

假占座事件：

```json
{
  "device_id": "SEAT_001",
  "event": "fake_occupy",
  "pressure_adc": 2450,
  "pir_state": false,
  "timestamp": 1750000000
}
```

心跳（无状态变化时每 60 秒一次，方案 §14）：

```json
{
  "device_id": "SEAT_001",
  "online": true,
  "timestamp": 1750000000
}
```

### 3.2 处理链路（编码规范 §17）

```
OneNetController  →  OneNetService  →  SeatService / RfidService  →  MySQL  →  WebSocketService
```

`OneNetController` 只接收和转发，**不处理业务**：

```java
@PostMapping("/device-data")
public Result<Void> receiveDeviceData(@RequestBody OneNetDataDTO dto) {
    oneNetService.handleDeviceData(dto);
    return Result.success();
}
```

`OneNetService.handleDeviceData()` 负责：

1. 校验 `device_id` 是否存在于 `seat` 表
2. 更新 `seat_shadow`：`pressure_adc` / `pir_state` / `alarm_flag` / `online=1` / `last_report_at`
3. 若 `alarm_flag` 由 0 变 1：座位业务状态置 `ALARM(4)`，写一条 `violation`（type=`FAKE_OCCUPY`）
4. 若 `alarm_flag` 由 1 变 0：业务状态从 `ALARM` 恢复（方案 §29）
5. 调 `WebSocketService` 广播 `SEAT_UPDATE` / `ALARM` / `DEVICE_STATUS`

`OneNetService.handleRfidEvent()` 负责：解析 `rfid_uid` → 调 `RfidService.signIn(rfidUid)`。

> RFID 不选择座位。RFID 只证明"这个人签到了"，签的是哪个座位由他当前的 RESERVED 预约决定（方案 §26）。

---

## 四、下行：后端控制设备

### 4.1 统一控制数据格式（编码规范 §42）

蜂鸣器测试：

```json
{ "service": "buzzer_ctrl", "duration_ms": 1000 }
```

OLED 显示：

```json
{ "property": "seat_display", "value": 0 }
```

修改阈值：

```json
{ "property": "adc_threshold", "value": 2000 }
```

强制释放：

```json
{ "service": "force_release" }
```

这是**业务层内部格式**，由 `OneNetService` 翻译成 OneNET OpenAPI 请求。前端只调后端的
REST 接口，永远看不到这一层。

### 4.2 触发入口

| 管理端操作 | REST 接口 | 下行内容 |
|---|---|---|
| 强制释放 | `POST /api/admin/seats/{id}/force-release` | `service: force_release` + `seat_display: 0` |
| 消除告警 | `POST /api/admin/seats/{id}/clear-alarm` | `seat_shadow.alarm_flag=0`，必要时同步 OLED/蜂鸣器 |
| 修改 ADC 阈值 | `POST /api/admin/devices/{id}/config` | `property: adc_threshold` |
| 测试蜂鸣器 | `POST /api/admin/devices/{id}/buzzer` | `service: buzzer_ctrl` |

每个操作都要写 `operation_log`（方案 §36）。

### 4.3 OpenAPI 调用

用 OkHttp 封装在 `OneNetService` 里，凭证从 `application.yml` 读取：

```yaml
onenet:
  base-url: ${ONENET_BASE_URL:https://open.iot.10086.cn}
  product-id: ${ONENET_PRODUCT_ID:}
  api-key: ${ONENET_API_KEY:}
```

> **需在控制台核对**：OneNET 存在旧版 OpenAPI（`open.iot.10086.cn`）与 OneNET Studio
> （`iot-api.heclouds.com`）两套体系，鉴权头名称与属性下发 / 服务调用的具体路径不同。
> 本文档不固化这些路径，请在你的产品所属体系下以官方文档为准，然后填进 `base-url`
> 与 `OneNetService` 的常量。设备侧 OneJSON 的 MQTT topic 形如
> `$sys/{pid}/{device-name}/thing/property/post`，同样请对照官方文档确认。
>
> 官方文档入口：
> - [OneNET API 调用说明](https://open.iot.10086.cn/doc/iot_platform/book/api/introduce.html)
> - [OneJSON 设备属性/事件](https://open.iot.10086.cn/doc/iot_platform/book/device-connect&manager/thing-model/protocol/OneJSON/property&event.html)

下发建议异步执行（`@Async`），不要阻塞管理员请求线程；下发失败只记日志，不回滚业务状态。

> **不做** `pending_display` 持久补偿队列（方案 §41 第 18 条明确删除）。下发失败即失败，
> 设备下次心跳会以云端最新状态为准。

---

## 五、设备在线判定

在线状态**由后端计算**，前端不自己判断（方案 §14）。

`DeviceOfflineTask` 每 60 秒执行：

```
now - seat_shadow.last_report_at > 120s  →  online = 0  →  广播 DEVICE_STATUS
```

120 秒 = 心跳周期 60 秒 × 2，允许丢一个包（编码规范 §25）。

---

## 六、WebSocket 广播格式（编码规范 §18）

地址 `/ws/seats?token=xxx`，小程序与 Web **共用同一套消息格式**。

```json
{ "type": "SEAT_UPDATE",       "seat_id": 1, "status": 2, "alarm": false, "online": true, "timestamp": 1750000000 }
{ "type": "DEVICE_STATUS",     "seat_id": 1, "online": false, "timestamp": 1750000000 }
{ "type": "ALARM",             "seat_id": 1, "alarm": true, "timestamp": 1750000000 }
{ "type": "RESERVATION_UPDATE","seat_id": 1, "status": 1, "timestamp": 1750000000 }
```

`WebSocketService` 必须复用配置了 `SNAKE_CASE` 的 `ObjectMapper`，否则会发出
`seatId`，前端两个端同时解析失败。

---

## 七、联调顺序（编码规范 §46）

1. `GET /api/seats` —— Postman 能拿到座位
2. `POST /api/reservations` —— 能预约
3. leave / return / release
4. **模拟 RFID**：`POST /api/onenet/rfid-event`，手工构造上面的 JSON
5. **模拟设备**：`POST /api/onenet/device-data`，手工构造属性上报
6. 浏览器连 `/ws/seats` 看广播
7. 接真实 STM32 + ESP8266
8. 小程序
9. Web 管理端

第 4、5 步不需要任何硬件，也不需要 OneNET 推送配好 —— 直接用 Postman 打后端接口即可，
这是把设备端和云端解耦调试的关键。

---

## 八、明确不做

- 前端持有任何 OneNET / 华为云 / 阿里云凭证
- 前端 MQTT 直连物联网平台
- 自建 EMQX Broker（方案 §41 第 20 条：不要双平台并行）
- OTA、固件升级、远程重启、设备分组、批量配置
- `pir_hold_time` 云端配置
- `pending_display` 持久补偿队列
