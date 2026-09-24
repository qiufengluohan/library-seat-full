# 03 · OneNET 接入与物模型设计

> 云平台**只使用 OneNET**，不再设计 EMQX 等自建 Broker，也不做双平台。
> 本文件是「设备端 / 云平台 / 后端」三方共用的契约说明；
> 代码里的同一份契约是后端 `library-seat` 的 `service/OneNetService.java`（identifier 常量）
> 与 `docs/OneNET接入指南.md`。
>
> **小程序端不出现任何 OneNET 字样** —— 见 §6。

---

## 1. 接入链路

```
STM32F103C8T6  ──UART──▶  ESP8266-01S  ──MQTT──▶  OneNET Studio
  传感器采集               Wi-Fi + MQTT           物模型 + 设备身份
                                                       │
                                        上行：HTTP 推送 │  下行：OpenAPI
                                                       ▼
                                          Spring Boot（library-seat）
                                                       │
                                                       │ MySQL + WebSocket
                                                       ▼
                                       微信小程序 / Vue3 Web 管理端
```

**OneNET 只承担「设备 ↔ 后端」这一段。** 小程序和管理端都只跟自建后端说话。

| 方向 | 通道 | 端点 |
| --- | --- | --- |
| 上行 · 设备数据 | OneNET → 后端 HTTP 推送 | `POST /api/onenet/device-data` |
| 上行 · 刷卡事件 | 同上 | `POST /api/onenet/rfid-event` |
| 下行 · 改阈值 | 后端 → OneNET OpenAPI | `POST {base}/iot-api/device/property/set` |
| 下行 · 蜂鸣器 / 强制释放 | 同上 | `POST {base}/iot-api/device/service/invoke` |

`base = https://iot-api.heclouds.com`（**OneNET Studio**，不是旧的 `open.iot.10086.cn`）。
下行设备一律按 `product_id` + `device_name` 寻址，`device_name` 就是 `SEAT_001` 这种字符串，
不是控制台里那串数字 ID。

> **安全铁律（编码规范 §42 / §53）**：api-key 和签名 token **永不出后端进程**。
> 前端绝对不能直接拼 OneNET 请求 —— 签名串放在小程序里会被反编译拿到，
> 拿到就等于拿到了对全馆所有设备的控制权。
> 管理端点「下发阈值」时，请求打到 `/api/admin/devices/{id}/config`，
> 由后端翻译成 OneNET 属性下发。

## 2. 物模型最终版本

**只保留规范第十六节定义的 7 个功能点。**

### 2.1 只读属性（设备 → 云平台 → 小程序）

| identifier | 名称 | 类型 | 单位 | 说明 |
| --- | --- | --- | --- | --- |
| `pressure_adc` | 压力值 | int32 | — | FSR402 的 ADC 原始值 0–4095 |
| `pir_state` | 人体红外 | bool | — | HC-SR501 输出，true 表示检测到人体 |
| `alarm_flag` | 告警标志 | bool | — | 双传感器融合判定为假占座时置 true |

### 2.2 可写属性（云端 → 设备）

| identifier | 名称 | 类型 | 说明 |
| --- | --- | --- | --- |
| `seat_display` | 座位显示 | enum | 0 空闲 / 1 已预约 / 2 使用中 / 3 暂离 / 4 异常 |
| `adc_threshold` | 压力阈值 | int32 | 判定「有人落座」的阈值，默认 2000 |

### 2.3 事件

| identifier | 名称 | 参数 |
| --- | --- | --- |
| `rfid_scan` | RFID 刷卡 | `rfid_uid`（string，十六进制大写卡号） |
| `fake_occupy` | 假占座告警 | `pressure_adc`（int32）、`pir_state`（bool） |

### 2.4 服务

| identifier | 名称 | 参数 |
| --- | --- | --- |
| `force_release` | 强制释放 | 无 |
| `buzzer_ctrl` | 蜂鸣器控制 | `duration_ms`（int32，默认 1000） |

### 2.5 已删除

`pir_hold_time` —— PIR 保持时间是 STM32 本地算法参数，不做云端配置。

## 3. 报文格式

### 3.1 设备状态上报

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

### 3.2 RFID 事件

```json
{
  "device_id": "READER_01",
  "event": "rfid_scan",
  "rfid_uid": "A1B2C3D4",
  "timestamp": 1750000000
}
```

### 3.3 假占座事件

```json
{
  "device_id": "SEAT_001",
  "event": "fake_occupy",
  "pressure_adc": 2450,
  "pir_state": false,
  "timestamp": 1750000000
}
```

### 3.4 心跳

设备有状态变化立即上报；没有变化时每 60 秒发一次心跳：

```json
{ "device_id": "SEAT_001", "online": true, "timestamp": 1750000000 }
```

云端按 `last_report_at` 判断在线：超过 120 秒没收到数据即 `online = false`。

### 3.5 MQTT Topic

| 方向 | Topic |
| --- | --- |
| 属性上报 | `$sys/{productId}/{deviceName}/thing/property/post` |
| 属性上报回复 | `$sys/{productId}/{deviceName}/thing/property/post/reply` |
| 事件上报 | `$sys/{productId}/{deviceName}/thing/event/{eventId}/post` |
| 属性设置（下行） | `$sys/{productId}/{deviceName}/thing/property/set` |
| 服务调用（下行） | `$sys/{productId}/{deviceName}/thing/service/{serviceId}` |

## 4. ESP8266 接入 AT 指令序列

```
AT                                  // 测试
AT+CWMODE=1                         // Station 模式
AT+CWJAP="WiFi名","密码"            // 连 Wi-Fi
AT+CIPSNTPCFG=1,8,"ntp.aliyun.com"  // 对时（MQTT 鉴权需要时间戳）
AT+CIPSTART="TCP","studio-mqtt.heclouds.com",1883
AT+CIPSEND=<len>
  // 发送 MQTT CONNECT 报文（clientId = deviceName，
  //   username = productId，password = 控制台生成的鉴权 token）
```

> ESP8266 只负责「联网 + 转发」：收到 STM32 的 JSON 字符串就发 MQTT，
> 收到 MQTT 下行就通过 UART 转给 STM32，**不解析任何业务**。

## 5. STM32 侧要点

### 5.1 状态结构体

```c
typedef struct {
    uint16_t pressure_adc;
    uint8_t  pir_state;
    uint8_t  alarm_flag;
    uint8_t  network_online;
} SeatSensorState;
```

STM32 **不定义** `RESERVED` / `USING` / `AWAY` —— 那是云端业务状态。
STM32 只知道「传感器状态 + 本地报警状态」。

### 5.2 假占座判定（只做简单版本）

```
pressure_adc > adc_threshold && pir_state == 1  →  正常有人使用
pressure_adc > adc_threshold && pir_state == 0  →  疑似物品占座
    持续达到时间窗口（建议 3 分钟，实测确定）
    → alarm_flag = true
    → 蜂鸣器报警 + OLED 提示
    → 上报 fake_occupy 事件
```

时间窗口用 tick 计时，不用 `delay` 阻塞，不用 FreeRTOS。

### 5.3 主循环

```c
while (1) {
    read_adc();          // 压力
    read_pir();          // 红外
    seat_logic();        // 融合判定 + 时间窗口
    rfid_scan();         // 读卡
    oled_refresh();      // 显示
    mqtt_report();       // 有变化立即上报，否则 60 秒心跳
    check_network();     // 断线重连
    handle_alarm();      // 蜂鸣器
}
```

## 6. 配置步骤

### 6.1 小程序端

**小程序只配一个东西：后端地址。**

打开 `miniprogram/utils/config.js`，填 `server.baseUrl`：

| 场景 | 值 |
| --- | --- |
| 本机调试 | `http://localhost:8080` |
| 局域网真机 | `http://192.168.1.10:8080` |
| 线上 | `https://api.example.com` |

然后在微信公众平台把**该域名**加进 request 合法域名；
开发期可在开发者工具「详情 → 本地设置」勾选「不校验合法域名」跳过。

实时推送的 socket 地址由同一个 `baseUrl` 推导（`https→wss`、`http→ws`），
**不需要再配第二个地址**；但上线时那个域名要**再加进「socket 合法域名」**一次，
那是另一个输入框，漏掉的表现为"数据都对，就是座位不自动刷新"（详见 `05-部署与运行.md` 第 5 节）。

`baseUrl` 留空时小程序不会发起任何请求，页面直接提示「尚未配置后端地址」，
**不显示任何本地假数据**。

> ### ⚠️ 小程序里不该出现 OneNET 的任何东西
>
> 早期方案让小程序直连 OneNET 查属性，已经废弃。`config.js` 里
> **没有也不该有** `dataSource`、`onenet.productId`、`onenet.authorization`、
> `onenet.devices` 这些字段；`utils/staticData.js`、`utils/thingModel.js`、
> `utils/onenet.js` 三个文件已删除；
> 合法域名里**不需要**加 `https://iot-api.heclouds.com`。
>
> 原因见 §1 的安全铁律：签名串打进小程序包就能被反编译提取，
> 泄露的是全馆设备的控制权。设备和座位的对应关系也搬进了后端数据库
> （`seat.device_id` 字段），不再由前端维护一份映射表 ——
> 前端那份表一旦和数据库不一致，会出现"页面显示 A 座、数据来自 B 座"的错乱。

### 6.2 后端

OneNET 凭证走**环境变量**，不写进任何提交到仓库的文件：

```bash
ONENET_API_KEY=控制台「产品详情 → API 访问」里的产品级 api-key
ONENET_PUSH_SECRET=自定义的一串随机值
```

`product_id`（`Wh08f3Q71p`）、base-url 和**每一个下行路径**都在
`library-seat/src/main/resources/application.yml` 的 `onenet:` 段里。
Studio 的 API 路径在开发期无法验证，万一和当前文档不一致，改一行配置即可，不用改代码。

### 6.3 OneNET 控制台

给产品配置**数据推送**（HTTP 推送 / 第三方平台推送），指向后端：

```
https://你的域名/api/onenet/device-data
```

推送请求要带上密钥，两种写法任选其一：

- 请求头 `X-Push-Secret: <ONENET_PUSH_SECRET 的值>`（推荐）
- 推送 URL 上拼 `?push_secret=<值>`

密钥不匹配时后端返回真实 HTTP 401，控制台的推送记录里能直接看到失败。

> `ONENET_PUSH_SECRET` 留空也能启动，但 `/api/onenet/**` 就处于免鉴权状态，
> 任何人都能伪造一条"设备上报"把座位状态刷成任意值。留空时后端每次请求都会打一条 WARN。

## 7. 「设备事实」与「业务状态」的分界

这一条是整个项目最容易写错的地方，务必遵守：

**原始传感器值只给管理端看，小程序只看合成后的业务状态。**

### 小程序端 —— 只有三个字段

来源：`GET /api/seats`（`SeatVO`）。小程序**没有**座位详情接口，
也**从不**读取 `pressure_adc` / `pir_state`。

| 字段 | 小程序端怎么用 |
| --- | --- |
| `status` 0~4 | **座位图配色、可预约判断的唯一依据**；`4` 才红闪 |
| `alarm` | 冗余布尔，方便列表直接画角标 |
| `online` | 叠加「离线」角标，**不覆盖**业务状态 |

代码位置：`utils/api.js` 的 `normSeat()`。
归一化层同时认 snake_case 和 camelCase，是为了后端 Jackson 命名策略万一被改坏时
页面不至于整片空白 —— 但这只是兜底，**后端约定是 snake_case**（编码规范 §4）。

### Web 管理端 —— 才看得到原始值

| 字段 | 来源接口 | 页面 |
| --- | --- | --- |
| `pressure_adc` `pir_state` `alarm_flag` `last_report_at` | `GET /api/admin/devices` | `DeviceManage.vue` |
| `pressure_adc` `pir_state` `last_report_at` | `GET /api/admin/seats/{id}` | 座位详情 |

管理端把它们当**排查用的观测量**展示（"压力值 2048、有人、3 秒前上报"），
同样**不反推业务状态** —— `constants/status.js` 和 `stores/seat.js` 里
都明确写了不存在"根据 pressure_adc 推断 status"的逻辑。

### 三个关键点

1. **`alarm_flag` 不由任何前端判断。** 设备上报的 `alarm_flag` 由后端合成进
   `seat.status`，翻成 `4`（异常）之后才发出去。前端看到 `status===4` 就红闪。
2. **离线不是座位状态。** `SeatStatus` 只有 0~4 五个值，离线是 `online` 这个
   附加布尔，渲染成角标。一个离线但上次是"使用中"的座位，仍然显示"使用中 + 离线角标"，
   不能显示成"空闲"。
3. **`online` 缺失时按在线处理**（`normSeat` 里就是这么写的），
   避免后端某个接口漏带这个字段时满屏"离线"。

## 8. 排错清单

按数据流方向分段查，先确定断在哪一段，再看那一行的排查方向。

### 设备 → OneNET

| 现象 | 排查方向 |
| --- | --- |
| 控制台里设备一直「未激活」 | ESP8266 没连上 MQTT。看 `AT+MQTTSTAT`、Wi-Fi 信号、设备密钥是否抄错 |
| 设备在线但没有属性数据 | 物模型 identifier 与设备实际上报的字段名不一致（**区分大小写**） |
| 设备一直显示离线 | ESP8266 供电不足（单独供电，别从 STM32 的 3V3 取电）；看控制台「最后在线时间」 |

### OneNET → 后端（上行推送）

| 现象 | 排查方向 |
| --- | --- |
| 控制台推送记录全是失败、后端返回 401 | `ONENET_PUSH_SECRET` 与控制台上填的密钥不一致；或忘了带 `X-Push-Secret` 头 |
| 后端收到推送但座位状态不变 | **第一件事**：打开 `application.yml` 里注释掉的 `com.example.libraryseat.service.impl: debug`，`OneNetServiceImpl.normalize` 会把原始报文整段打出来，照着改 `normalize` 一个方法即可，业务代码不用动 |
| 后端日志刷「device_id 没有对应座位」 | 如果是 `READER_01`，**这是正常的** —— 共享读卡器不占座位。如果是 `SEAT_xxx`，说明 `seat.device_id` 没填或与设备名不符 |
| 后端反复收到同一条推送 | 后端返回了 500（例如数据库连不上），平台按重试策略重推。修好根因即可，**不要**把异常吞掉 |

### 后端 → OneNET（下行）

| 现象 | 排查方向 |
| --- | --- |
| 接口返回 200 但设备没反应 | 下行是 `@Async` 且失败只记日志，**200 只代表指令已交给平台**。查后端日志里的下行报错 |
| 日志「OneNET 下行未配置」 | `ONENET_API_KEY` 没设。此时上行照常，但改阈值 / 蜂鸣器 / 显示恢复全部跳过 |
| 下行一律 401 | token 签名错。待签串必须是 `et\nmethod\nres\nversion` 这个**固定顺序**，且 HMAC 密钥是 api-key 的 **base64 解码字节**，不是原文 |
| 下行 404 | `onenet.property-set-path` / `onenet.service-invoke-path` 与 Studio 当前文档不符，改这两行配置即可 |

### 后端 → 前端

| 现象 | 排查方向 |
| --- | --- |
| 小程序提示「尚未配置后端地址」 | `config.js` 的 `server.baseUrl` 是空的 |
| 开发者工具报域名不合法 | 微信公众平台没加**后端**域名；调试期勾选「不校验合法域名」 |
| 微信登录一直失败、后端日志 `errcode=40164` | 服务器公网出口 IP 不在小程序 **IP 白名单**里（公众平台 → 开发管理 → 开发设置）。本机联调时要加家里宽带的出口 IP |
| 微信登录 `errcode=40025 / 40125` | `WECHAT_APP_ID` 与 `WECHAT_APP_SECRET` 不配对 |
| 管理端所有接口都弹「登录已过期」 | token 过期（默认 720 分钟）或 `JWT_SECRET` 改过；重新登录即可 |
| 管理端字段全是 `undefined` | 后端 `spring.jackson.property-naming-strategy: SNAKE_CASE` 被改掉了，`device_id` 变成了 `deviceId` |
| 管理端 409 冲突弹了错误框 | 后端把 409 改成了真实 HTTP 状态码。约定是 **400/403/404/409 一律 HTTP 200 + 响应体 code**，`request.js` 的 `error.silent = body.code === 409` 在**成功**拦截器里 |
| 座位页不自动刷新 | WebSocket 没连上。检查 `/ws/seats` 的 token 查询参数，以及后端有没有广播 |
