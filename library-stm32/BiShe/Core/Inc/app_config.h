/**
  ******************************************************************************
  * @file    app_config.h
  * @brief   全局配置：设备身份、网络凭证、OneNET 主题、时序参数、阈值
  *
  * @note    ⚠️ 本文件含**真实凭据**（WiFi 口令 + OneNET 设备 token），烧录前按需核对：
  *            - WIFI_SSID / WIFI_PASS：座位所在 WiFi
  *            - ONENET_DEVICE_TOKEN：OneNET 控制台「设备管理-设备详情-鉴权信息」
  *              用 Token 生成工具生成（version=2018-10-31&res=...&et=...&method=md5&sign=...）
  *            - DEVICE_NAME 必须与 OneNET 设备名、后端 seat.device_name 三者一致
  *              （后端按 device_name 精确匹配，写错就收不到数据）
  *
  *          ⚠️ **不要把本文件推到公开仓库**：下面三个宏是明文真值。
  *             要上传 GitHub / 开源前，先换成占位符，或把凭据拆成不进版本库的私有头。
  *
  * @note    引脚宏不在这里定义！全部使用 CubeMX 生成的 main.h 里的宏
  *          （如 HC_SR505_IN_Pin / RFID_RST_Pin / BUZZ_I_O_Pin / FSR1_ADC_Pin），
  *          这样以后在 CubeMX 里改引脚，代码会自动跟着走，不会出现"两套引脚"。
  ******************************************************************************
  */

#ifndef __APP_CONFIG_H
#define __APP_CONFIG_H

#include "main.h"

/* ============ 固件版本（显示在 OLED 第 0 行，用来确认烧进去的是哪一版） ============ */
/**
 * @note  排查时最常见的坑就是"以为烧了新固件，其实还是旧的"。
 *        状态面板第 0 行显示 `SEAT_001 <FW_VERSION> <当前判定通道>`，
 *        例：`SEAT_001 V12 PA1` ⇒ V12 版，判定用 PA1（FSR402 座子）。
 *        · 看到 `V:xxxx K1:LOG` 且第 0 行没有 V12 ⇒ 还是旧固件。
 *
 * @note  V12（2026-10-19）：修 SUBSCRIBE 固定头 0x80→0x82（OneNET 判协议错误
 *        `CloseDueToProtoError` 的直接原因），并加 SUBACK 等待/显示。
 * @note  V13（2026-10-19）：修**事件上报 topic**（不能带 `/{eventId}`，标识符放 params）
 *        + 补齐事件输出参数（少一个平台回 2308）。原来一触发告警就整条连接被平台断开。
 * @note  V14（2026-10-19）：重写 MFRC522 驱动（ComIrqReg 判位/清零、FIFO 冲刷、读完补 HALT）。
 * @note  V16（2026-10-19）：回退 V15 的 LED / 刷卡提示方案（该方案取消），
 *        恢复「假占座告警时蜂鸣器响 1 s」。设备仍是**单座位**、RFID 在 SEAT_001 内。
 * @note  V17（2026-10-22）：**双座位改造**（docs/05-双座位改造方案.md 定稿）。
 *        · 一包两座位：pressure_adc_1/_2、pir_state_1/_2、alarm_flag_1/_2；
 *        · 每座位 3 只三色灯（绿=空闲 / 黄=使用中 / 红闪=告警），新增 led.c；
 *        · 蜂鸣器**不再参与告警**，只做「换新卡短鸣 60 ms」与云端 buzzer_ctrl；
 *        · K1 短按翻页 / K1 长按切当前座位；K2 短按标当前座位 / K2 长按全标定。
 *        真机看到第 0 行 `SEAT_001 V17` 才说明这一版烧进去了。
 */
#define FW_VERSION          "V17"

/* ============ 设备身份（必须改） ============ */
#define DEVICE_NAME         "SEAT_001"        /* 座位设备名，三端必须一致 */
#define ONENET_PRODUCT_ID   "Wh08f3Q71p"      /* OneNET 产品 ID */

/* ============ WiFi / OneNET 凭据（真值） ============ */
/* 🔴 本文件会被 Git 跟踪：提交 / 推送前先确认这三个宏里没有不想公开的凭据。 */
#define WIFI_SSID  "WIFI_SSID"
#define WIFI_PASS  "WIFI_PASS"
#define ONENET_DEVICE_TOKEN "ONENET_DEVICE_TOKEN"

/* ============ OneNET MQTT 接入参数 ============ */
/**
 * @brief 接入地址必须用 **Studio 实例**的，不是旧版标准版的
 *
 * @note  ⚠️ 2026-10-14 修正：原来是 `183.230.40.39`（**旧版 OneNET 多协议接入**的地址），
 *        本项目是 **OneNET Studio 实例**（product_id 形如 Wh08f3Q71p、
 *        物模型 json 里带 `industryId`、后端也走 `iot-api.heclouds.com`），
 *        必须用 Studio 的接入点，否则**MQTT 一直连不上**（现象是 CONNACK 超时）。
 *
 *        Studio 官方接入点（open.iot.10086.cn 文档）：
 *          非加密 MQTT ：studio-mqtt.heclouds.com   1883  ← 本工程用这个
 *          加密   MQTTS：studio-mqtts.heclouds.com  8883  （ESP8266 AT 透传做 TLS 很麻烦，不用）
 *
 *        与本项目 `docs/02-OneNET接入与物模型设计.md` 里写的
 *        `AT+CIPSTART="TCP","studio-mqtt.heclouds.com",1883` 一致。
 */
#define ONENET_MQTT_HOST   "studio-mqtt.heclouds.com"
#define ONENET_MQTT_PORT   1883

/* 主题（OneJSON 物模型规范，$sys/{pid}/{device}/...） */
#define TOPIC_PROP_POST    "$sys/" ONENET_PRODUCT_ID "/" DEVICE_NAME "/thing/property/post"
#define TOPIC_PROP_SET     "$sys/" ONENET_PRODUCT_ID "/" DEVICE_NAME "/thing/property/set"
#define TOPIC_SVC_INVOKE   "$sys/" ONENET_PRODUCT_ID "/" DEVICE_NAME "/thing/service/"
/* 下行订阅用一个通配 topic 一次覆盖 property/set 与 service/invoke（OneNET 支持 # 通配） */
#define TOPIC_DOWNLINK     "$sys/" ONENET_PRODUCT_ID "/" DEVICE_NAME "/thing/#"

/**
 * @brief 事件上报 topic —— ⚠️ **topic 里不带事件标识符**
 *
 * @note  🔴 2026-10-19 修正（真机复现 + 官方文档确认）：
 *        原来是 `.../thing/event/{eventId}/post`，平台收到后**直接断开 TCP**，
 *        OneNET 侧记 `offline_reason = CloseDueToProtoError`。
 *        官方《设备属性/事件》§4 规定的事件上报 topic 是：
 *              $sys/{pid}/{device-name}/thing/event/post
 *        事件标识符放在 **payload 的 params 里**：
 *              {"id":"1","version":"1.0","params":{"fake_occupy":{"value":{...}}}}
 *        响应 topic 为 `.../thing/event/post/reply`。
 *
 * @note  ⚠️ 另一个坑：物模型里的事件**输出参数必须齐全**，少一个平台就回
 *        `code=2308 event param count error`。本产品两个事件的输出参数是：
 *          fake_occupy → pressure_adc / pir_state / device_id / timestamp / seat_no（**5 个**）
 *          rfid_scan   → rfid_uid / device_id / timestamp（3 个）
 *        设备没有 RTC，timestamp 统一填 0；后端 TimeUtil.fromEpoch(0) 返回 null，
 *        会自动回退成"收到报文的时间"，不会把 last_report_at 写成 1970。
 *
 * @note  🔴 seat_no（V17 双座位新增）：1 = 座位1（PA0/PA7），2 = 座位2（PA1/PA6）。
 *        事件里必须带，否则后端不知道是哪个座位报的警。
 */
#define TOPIC_EVENT_POST   "$sys/" ONENET_PRODUCT_ID "/" DEVICE_NAME "/thing/event/post"

/* ============ 座位拓扑（V17 双座位） ============ */
/**
 * @brief 一台设备带几个座位
 * @note  座位下标 i（0/1）与云端座位号 seat_no（1/2）差 1，转换统一用 SEAT_NO(i)。
 *        座位 i 的硬件映射见 sensors.c：
 *          i=0 → FSR1=PA0(ADC1_IN0) + PIR1=PA7 + LED1/2/3(PB0/PA5/PA4)
 *          i=1 → FSR2=PA1(ADC2_IN1) + PIR2=PA6 + LED4/5/6(PA8/PA9/PA10)
 */
#define SEAT_COUNT          2U
#define SEAT_NO(i)          ((uint8_t)((i) + 1U))

/* ============ 时序参数（毫秒） ============ */
#define SENSOR_POLL_MS      1000     /* 传感器采集周期 */
#define HEARTBEAT_MS        60000    /* 属性心跳上报周期（保活） */
#define PING_MS             50000    /* MQTT PINGREQ 周期（< keepalive 120s） */
#define FAKE_OCCUPY_MS      180000   /* 假占座判定：压力有/红外无 持续 3 分钟 */
#define ESP_AT_TIMEOUT_MS   8000     /* 单条 AT 指令应答超时 */
#define RECONNECT_DELAY_MS  5000     /* 断线重连间隔 */

/* ============ 按键时序（V17 双座位，docs/05 §4.1） ============ */
/**
 * @brief K1/K2 的短按与长按判定（全部毫秒）
 * @note  K1：短按 = 翻页（< KEY_SHORT_MS）；按住 ≥ KEY_K1_LONG_MS **未松开**就切座位，
 *           并置"已处理"，松开时不再翻页。600~1000 ms 之间是死区（设计口径，不是 bug）。
 *        K2：短按 = 标定当前座位；按住 ≥ KEY_K2_LONG_MS 进入「全部标定」待确认，
 *           3 s 内再短按一次才执行（防误触把两路零点一起冲掉）。
 */
#define KEY_DEBOUNCE_MS     20       /* 按键消抖 */
#define KEY_SHORT_MS        600      /* 松开时 < 600 ms = 短按 */
#define KEY_K1_LONG_MS      1000     /* K1 按住 ≥ 1 s（未松开）= 切换当前座位 */
#define KEY_K2_LONG_MS      2000     /* K2 按住 ≥ 2 s（未松开）= 全标定待确认 */
#define KEY_CAL_CONFIRM_MS  3000     /* 待确认窗口：3 s 内再短按一次才执行 */
#define KEY_SEAT_BLINK_MS   250      /* 切座位后 [S1] 闪烁半周期（两次 = 1 s） */

/* ============ 三色灯（V17） ============ */
#define LED_BLINK_MS        250      /* 告警红灯翻转周期 ⇒ 2 Hz（非阻塞，见 led.c） */
#define LED_CAL_FLASH_MS    300      /* 标定成功：6 只灯全亮 0.3 s 作反馈 */

/**
 * @brief 上电跑一遍「灯序自检」：逐个点亮 LED1~LED6，OLED 同步显示它在哪个引脚
 *
 *   = 0（默认）正常启动
 *   = 1 **装配/排查时临时改成 1**，烧一次就能确认：
 *         · 哪只灯不亮（LED 坏 / 焊反 / 断线）
 *         · 哪只灯常亮不受控（多半被外部拉高 / 焊到了 3.3V）
 *         · 灯的物理位置是否和 LED1~LED6 的装配约定一致（焊错位置）
 *       跑完记得改回 0（会阻塞约 6 秒）。
 *
 * @note 判定口径：自检时**只有当前这一只**该亮，其余 5 只必须全灭。
 *       若某只"该亮时也亮、只是暗一点"，查它的 330Ω 是否虚焊（阻值变大）。
 */
#ifndef LED_SELFTEST_ON_BOOT
#define LED_SELFTEST_ON_BOOT     0
#endif

/* ============ 蜂鸣器（V17 口径） ============ */
/**
 * @brief 蜂鸣器**不参与告警**，只有三种响法：
 *          ① 换了一张**新卡**（UID 变了）→ 短鸣 BUZZER_CARD_MS
 *          ② 云端 buzzer_ctrl 服务 → 按下发的 duration_ms
 *          ③ OLED 上电找不到 → 3 短鸣（提示 I2C 接线）
 *        假占座告警只有红闪 + OLED + 管理端，不再响（docs/05 §3.3）。
 */
#define BUZZER_CARD_MS      60

/* ============ 运行模式开关 ============ */

/**
 * @brief 是否上电就自动连 WiFi / OneNET
 *
 *   0（**当前值 = 台架联调**）= 只跑本地逻辑：采集、双感应融合、OLED、刷卡、蜂鸣器；
 *              不联网。按键、刷屏都正常跟手。
 *   1        = 走完整连接状态机（AT→WiFi→TCP→透传→MQTT→订阅→运行）。
 *
 * @note 🔴 **改成 1 会让按键/刷屏变得"没反应"**，这不是按键坏了：
 *        连接状态机是**阻塞**的 —— `ESP_ATReady()` 单次最长等 8 s、
 *        `ESP_JoinWiFi()` 最长 20 s、失败后还有 `HAL_Delay(5000)` 重试间隔。
 *        这段时间主循环被卡在里面，`UI_Task()`（按键扫描 + 刷屏）几秒才轮到一次，
 *        而且 `SEAT_App_Process()` 在未连上时会**直接 return，连传感器都不采**。
 *        ⇒ 台架上调 FSR / 按键 / OLED 时**必须保持 0**；
 *          要测云端再临时改 1，并接受这段时间按键迟钝。
 *
 * @note ⚠️ 改成 1 之前，先核对 WIFI_SSID / WIFI_PASS / ONENET_DEVICE_TOKEN 是否为当前有效值。
 */
#ifndef APP_AUTOSTART_CONNECT
#define APP_AUTOSTART_CONNECT  1
#endif

/* ============ ESP8266 链路自检（联调期用，测通后可关掉） ============ */

/**
 * @brief 上电自动跑一次 ESP 链路自检，并把结果显示在 OLED 上
 * @note  ⚠️ 会**阻塞几秒**（AT 探测每档最长 2s、最多试 2 档）。联调完请改回 0。
 * @note  与 APP_AUTOSTART_CONNECT=1 同时开着时：自检先跑（会显示结果），
 *        之后连接状态机再接管 OLED，所以看结果要**抓上电后的头几秒**。
 *        建议联调阶段先把 APP_AUTOSTART_CONNECT 设成 0，只看自检结果。
 */
#define ESP_SELFTEST_ON_BOOT     1

/**
 * @brief 自检方式（二选一，两种状态不能同时存在）
 *          0 = ② **AT 探测**（默认）：ESP 模块**接好**，发 "AT" 等 "OK"，
 *              并自动在 115200 / 9600 之间探测模块实际波特率。
 *          1 = ① **回环自测**：**拔掉 ESP 模块**，用一根杜邦线把
 *              **PB10 与 PB11 短接**（STM32 自己发、自己收），
 *              用来单独验证 STM32 侧的 USART3 + RX 中断 + 环形缓冲是否正常。
 *
 * 排错逻辑：先跑 ① 通过 ⇒ STM32 侧没问题，剩下的锅在模块/接线/供电；
 *           ① 不通过 ⇒ 先别怀疑模块，是 STM32 侧或短接线的问题。
 */
#define ESP_SELFTEST_LOOPBACK    0

/* ============ OLED 自检图案开关 ============ */

/**
 * @brief 上电显示 OLED 自检图案（全亮 → 全灭 → 顶/中/底三行字）
 *
 *   = 0（默认）正常显示状态面板/事件日志
 *   = 1 **只显示自检图案**，用来判断"屏幕上的怪字符"到底是
 *       ① 显示屏寻址/控制器不匹配（全亮时不是整屏亮），还是
 *       ② 固件根本没烧进去（那屏上就不是本固件画的）
 *
 *   ⚠️ 诊断完请改回 0。
 */
#define OLED_TEST_PATTERN        0

/* ============ 蜂鸣器开关 ============ */

/**
 * @brief 是否接了有源蜂鸣器（PB5，高电平触发）
 *
 *   1（**当前值：已接蜂鸣器**）
 *       ⇒ 以下三处会响：
 *         · 换了一张**新卡**（UID 变了）短鸣 60 ms —— **假占座告警不再响**
 *         · 云端 `buzzer_ctrl` 服务按 `duration_ms` 鸣叫
 *         · OLED 探测失败时 3 短鸣（提示 I2C 接线问题）
 *
 *   0 ⇒ 没接蜂鸣器时用：`BUZZER_On/Off/Beep/Task` 全部编译成空操作，
 *        并且**省掉"OLED 找不到"时那 3 短鸣的 1.2 秒白等**。
 */
#ifndef BUZZER_ENABLED
#define BUZZER_ENABLED           1
#endif

/* ============ 传感器配置 ============ */

/**
 * @brief 哪几路 FSR 参与判定（按位选通）
 *
 *   bit0 = PA0（FSR1，座位 1）
 *   bit1 = PA1（FSR2，座位 2）
 *
 * @note  🔴 V17 双座位改造后固定为 **0x03**：一路 FSR 就是一个座位，两路都必须参与，
 *        **不能再改成 0x01 / 0x02** —— 那样会有一个座位永远判不出有人
 *        （编译期 #error 会把非法值挡住，但 0x01/0x02 本身是合法值，只能靠这条注释提醒）。
 *
 * @note  每路**各用各的零点与阈值**（见 sensors.c），并且"空载基线不像话"的通道会被
 *        自动判为**无效**（悬空/没接），所以「没插传感器的那一路不会误报」这条性质仍然保留。
 *
 *        ⚠️ 原理图把 FSR1 画到 PA00、FSR402 画到 PA01，但 2026-10-18 实测**正好相反**：
 *          · 插 **FSR1 座子** ⇒ 变化的是 **P1（PA1）**（按下≈4095、松开≈0）
 *          · 插 **FSR402 座子** ⇒ 变化的是 **P0（PA0）**（渐变，量程正常）
 *          可能是 PCB 布局把两个座子的网络对调了，或丝印与原理图不一致。
 *          **以实测为准**。双座位后这件事只影响"哪个座位挂哪片 FSR"，
 *          装好后按 K2 分别标定即可，不需要改这里。
 */
#define FSR_ACTIVE_MASK   0x03U

/** 某一路是否参与判定（ch = 0(PA0) / 1(PA1)） */
#define FSR_CH_ACTIVE(ch) (((FSR_ACTIVE_MASK) >> (ch)) & 0x01U)

/* ============ 传感器阈值 ============ */
/**
 * 🔴 判定口径（2026-10-18 改）：**相对阈值** = 上电空载基线 + 增量
 *
 *   以前是一个写死的绝对数（800）。问题是：FSR 贴在坐垫下面时
 *   ① 坐垫自重就会预压出一个非零基线（几十~几百）；
 *   ② 换一片 FSR / 换一块板 / 焊点接触电阻不同，空载值和灵敏度都会变。
 *   固定 800 在这块板子上很可能是"轻坐压不到、一直判无人"，
 *   或者反过来"空载就漂到 800 以上、一直判有人"。
 *
 *   现在 FSR_Init() 会在上电时采一次空载基线 g_fsr_zero[]，
 *   SENSORS_Init() 用 阈值 = 基线 + PRESSURE_DELTA_DEF（夹在 [MIN, 4095]）。
 *
 *   标定：K1 按两下进 FSR 调试页，看「空载时 MIN/MAX」和「坐上去时 MAX」，
 *        两个值差多少，就把 PRESSURE_DELTA_DEF 定在这个差的 30%~50%。
 *   ⚠️ 上电那一刻座位上不要坐人，否则基线会被抬高。
 */

/** 相对空载基线的增量阈值（ADC 计数）。120 ≈ 97 mV ≈ FSR 侧约 330 kΩ，很轻的力就能触发 */
#define PRESSURE_DELTA_DEF       120U

/** 增量阈值的下限：防止基线噪声导致过度灵敏 */
#define PRESSURE_DELTA_MIN        40U

/**
 * @brief 空载基线「可信」的上限
 * @note  有 10.1k 下拉且空座时基线只有 **0~5**；引脚悬空时会在 **几百~900** 乱漂。
 *        所以 >200 视为"这路没接好/悬空"，**该路自动退出判定**（不会误报有人）。
 */
#define PRESSURE_ZERO_MAX        200U

/**
 * @brief 空载基线「高但可信」的下限
 * @note  上电时座位上就有人 ⇒ 基线会是几千。这种通道仍然保留，
 *        但改用绝对阈值 PRESSURE_THRESHOLD_DEF，避免把"有人"学成零点。
 *        处在 (ZERO_MAX, ZERO_OCCUPIED) 之间的，判为半悬空 ⇒ 同样退出判定。
 */
#define PRESSURE_ZERO_OCCUPIED  1500U

/**
 * @brief 兜底绝对阈值（基线不可信时才用）
 * @note  云端下发的 adc_threshold 也是**绝对阈值**，会直接覆盖它（见 seat_app）。
 */
#define PRESSURE_THRESHOLD_DEF   800U

#define ADC_SAMPLE_COUNT    5        /* 滑动平均窗口（fsr.c 内部用 FSR_FILTER_LEN，此处仅作记录） */
#define PIR_CHECK_COUNT     2        /* 连续 N 次高电平才判定有人 */
#define PIR_HOLD_MS         10000    /* 过渡态窗口：压力消失但红外仍高，超过它就回 IDLE（HC-SR505 保持约 8s） */
#define PIR_STUCK_MS        300000   /* 连续 5 分钟"没压力却一直高" ⇒ 判定 PIR 可疑（接反/型号不对/坏） */

/* ============ 其它外设参数 ============ */
/* OLED I2C 地址（SSD1306 典型 0x78 写 / 0x79 读；多数模块 0x3C 7 位地址） */
#define OLED_I2C_ADDR       0x3C

/* ============ 上电时序（🔴 别调成 0，否则冷上电屏幕不亮） ============ */

/**
 * @brief 主循环起来之前，等外设电源稳定的时间（ms）
 *
 *   **为什么需要**：SSD1306 模块没有接到 MCU 的 RST 脚（本工程只有 PB6/PB7 两根 I2C），
 *   模块靠自己内部的 RC 上电复位；datasheet 要求 VDD 稳定后 **≥100 ms** 才接受命令，
 *   电荷泵升压还要更久。而 STM32 从复位到跑进 `OLED_Init()` 只要十几毫秒（HSE+PLL，
 *   ADC 自校准才几 ms）。
 *
 *   ⇒ 冷上电时命令全部 NACK，屏幕保持 POR 默认的"关显示"状态，一直黑。
 *   ⇒ 而"重新下载程序"只复位 MCU、**不断 OLED 的电**（屏早就初始化好了），所以能亮 ——
 *      这就是"必须重新下一次程序才会点亮"的真正原因。
 *
 *   取值：250~300 够用；板子电源上升慢（大电容、USB 供电弱）可以加到 500。
 */
#ifndef APP_POWER_ON_DELAY_MS
#define APP_POWER_ON_DELAY_MS     300
#endif

/**
 * @brief 打开电荷泵（0x8D,0x14）之后、发 0xAF（开显示）之前的等待（ms）
 *
 *   电荷泵要把 VCC 从 3.3V 抬到 ~7.5V 驱动 OLED 面板，升压没稳就开显示 ⇒ 黑屏或花屏。
 *   数据手册的典型初始化流程在这一步留 100 ms。
 */
#ifndef OLED_CHARGE_PUMP_DELAY_MS
#define OLED_CHARGE_PUMP_DELAY_MS 100
#endif

/**
 * @brief OLED 地址探测的重试次数与间隔（冷上电窗口内设备可能还没应答）
 *
 *   探测失败不会重来是以前的隐患：一次没应答就整场黑屏，只能重新烧录救回来。
 */
#ifndef OLED_PROBE_RETRY
#define OLED_PROBE_RETRY          3
#endif
#ifndef OLED_PROBE_RETRY_MS
#define OLED_PROBE_RETRY_MS       120
#endif


/* ============ 外设句柄引用（本工程实际用到的那些） ============ */
extern UART_HandleTypeDef huart3;    /* ESP8266-01S：PB10=TX / PB11=RX */
extern SPI_HandleTypeDef  hspi2;     /* MFRC522：PB13/14/15 + PB12 软件片选 */
extern I2C_HandleTypeDef  hi2c1;     /* SSD1306：PB6/PB7 */
extern ADC_HandleTypeDef  hadc1;     /* FSR1：PA0 */
extern ADC_HandleTypeDef  hadc2;     /* FSR2：PA1（fsr.c 无条件引用，MX_ADC2_Init 必须保留） */

#endif /* __APP_CONFIG_H */
