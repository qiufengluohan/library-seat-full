/**
  ******************************************************************************
  * @file    seat_app.c
  * @brief   座位终端业务编排实现
  *
  * @note    两种运行模式由 app_config.h 的 APP_AUTOSTART_CONNECT 决定：
  *            = 0（默认）本地模式：只做采集/融合/OLED/刷卡/蜂鸣，不联网
  *            = 1        联网模式：连接状态机
  *                        AT就绪 → 连WiFi → TCP连Broker → 透传 → MQTT CONNECT
  *                        → 订阅下行 → RUNNING（采集/融合/RFID/上报/下行分发）
  *
  *          ⚠️ 连接状态机里有阻塞等待（AT 指令、WiFi 连接最长 20s），
  *             所以"连接期间"不做采集；连上之后才按 SENSOR_POLL_MS 周期采集。
  ******************************************************************************
  */

#include "seat_app.h"
#include "app_config.h"
#include "logging.h"
#include "esp8266.h"
#include "onenet.h"
#include "sensors.h"
#include "fusion.h"
#include "oled_ssd1306.h"
#include "mfrc522.h"
#include "buzzer.h"
#include "led.h"
#include "json_util.h"
#include "esp_selftest.h"
#include "ui.h"
#include "main.h"

#include <string.h>
#include <stdlib.h>
#include <stdio.h>

/* ---------- 连接状态机 ---------- */
typedef enum {
  ST_DISCONNECTED = 0,
  ST_AT_READY,
  ST_WIFI,
  ST_TCP,
  ST_TRANSPARENT,
  ST_MQTT,
  ST_SUBSCRIBE,
  ST_RUNNING
} conn_state_t;

static conn_state_t g_conn = ST_DISCONNECTED;

/* ---------- 业务状态 ---------- */
/* 阈值不再在这里另存一份：唯一真源是 sensors.c 的 g_thr[]
   （上电=空载基线+增量，云端下发后=云端绝对阈值，K2 重标定后=新基线+增量）。
   以前这里有一份 g_adc_threshold，K2 重标定后它会变成旧值，判定就跟着错。 */
static uint8_t  g_seat_display = 0;   /* 云端下发的 OLED 显示码 0-4（设备级，整屏） */

/* 最近一次采集的结果（每个座位一份，下标 = 座位下标） */
static uint16_t g_last_pressure[SEAT_COUNT];
static bool     g_last_pir[SEAT_COUNT];
static bool     g_last_alarm[SEAT_COUNT];

/* 上报去抖快照（同上，每座位一份） */
static uint16_t g_prev_pressure[SEAT_COUNT];
static bool     g_prev_pir[SEAT_COUNT];
static bool     g_prev_alarm[SEAT_COUNT];

/* 计时基准 */
static uint32_t g_last_poll  = 0;
static uint32_t g_last_heart = 0;
static uint32_t g_last_ping  = 0;
static uint32_t g_last_pong  = 0;

/* RFID 去重与显示 */
#define RFID_READ_INTERVAL_MS  5000
static uint8_t  g_last_uid[4]   = {0};
static uint32_t g_last_uid_time = 0;
static char     g_uid_line[20]  = "";

/* 事件日志用的"压力过阈值"上次快照 */
static bool g_prev_pressed[SEAT_COUNT];

/* 说明：OLED 的显示逻辑已统一收进 ui.c
   （两座位状态面板 + 事件日志 + 标定页 + 按键长短按 + 节流刷新）。
   本文件只负责在采集/通信时，把每个座位的"变化"喂给 UI_Log() 与 UI_SetSeatStatus()。 */

/* ---------- 本次上电的复位原因 ---------- */
/**
 * @brief 读出 RCC 的复位标志，判断"上一次是怎么重启的"
 * @note  正常上电时 PORRST 与 PINRST 会**同时**置位（NRST 在电源爬升期间被拉低），
 *        所以先判"明确非掉电"的原因（软件/看门狗），最后才判 POR。
 *
 *        排查"上电一会就断电"时这条信息是决定性的：
 *          POR  ⇒ 电源真的掉过（掉电/欠压）→ 查供电、负载、接触
 *          PIN  ⇒ 只有 NRST 被拉低（没有掉电）→ 查复位脚干扰/按键/电容
 *          IWDG/WWDG ⇒ 看门狗复位（本工程未启用，出现即异常）
 *          SW   ⇒ 软件主动复位（本工程未使用）
 */
static const char *reset_cause_str(void)
{
  if (__HAL_RCC_GET_FLAG(RCC_FLAG_SFTRST))  return "SW";
  if (__HAL_RCC_GET_FLAG(RCC_FLAG_IWDGRST)) return "IWDG";
  if (__HAL_RCC_GET_FLAG(RCC_FLAG_WWDGRST)) return "WWDG";
  if (__HAL_RCC_GET_FLAG(RCC_FLAG_LPWRRST)) return "LPWR";
  if (__HAL_RCC_GET_FLAG(RCC_FLAG_PORRST))  return "POR";
  if (__HAL_RCC_GET_FLAG(RCC_FLAG_PINRST))  return "PIN";
  return "?";
}

/* ---------- 初始化 ---------- */

void SEAT_App_Init(void)
{
  static const char *s_rst_cause = "?";
  uint8_t seat;

  /* 🔴 必须在任何其它初始化之前读，读完清掉，免得下次被脏标志骗了 */
  s_rst_cause = reset_cause_str();
  __HAL_RCC_CLEAR_RESET_FLAGS();

  /* 🔴 冷上电必须等外设电源稳定，再碰 I2C/SPI/UART。
     MCU 从复位到执行到这里只要十几毫秒，而 SSD1306（无 RST 脚，全靠模块内部 RC 复位）、
     MFRC522、ESP8266 都比 MCU 慢一个数量级。
     缺这一段的表现就是：**断电重新上电屏幕全黑，重新下载一次程序（OLED 没断电）就亮**。 */
  HAL_Delay(APP_POWER_ON_DELAY_MS);

  ESP_Init();
  SENSORS_Init();       /* 两路 ADC 自校准 + 两个座位各采一次空载基线 */
  OLED_Init();
  MFRC522_Init();
  BUZZER_Init();
  LED_Init();           /* 6 只三色灯先全灭，避免上电瞬间乱亮 */
  FUSION_Init();        /* 两个座位的融合状态清零 */

#if LED_SELFTEST_ON_BOOT
  /* 灯序自检（app_config.h 开关）：逐个点亮 LED1~LED6，OLED 显示它在哪个脚。
     装配后跑一次就能定位"哪只不亮 / 哪只常亮 / 灯序是否焊错"，跑完把开关改回 0。
     ⚠️ 阻塞约 6 秒。 */
  LED_SelfTest();
#endif

#if BUZZER_ENABLED
  /* OLED 没应答时用蜂鸣器报警 —— 屏幕不亮时这是唯一能反馈给人的通道：
       3 短鸣 = I2C 上找不到 OLED（查接线 GND/VCC/SCL/SDA 是否接反、是否上电）
     ⚠️ 没接蜂鸣器时整段会被编译掉，省掉这 1.2 秒白等。 */
  if (g_oled_found == 0U)
  {
    uint8_t k;
    for (k = 0; k < 3U; k++)
    {
      BUZZER_Beep(150);
      HAL_Delay(250);
    }
  }
#else
  /* 没接蜂鸣器：不报警。OLED 有没有找到请看 g_oled_found / g_oled_i2c_err */
  (void)g_oled_found;
#endif

  /* 显示层初始化：默认进"状态面板"，按 K1 可切到"事件日志" */
  UI_Init();
#if APP_AUTOSTART_CONNECT
  UI_Log("BOOT ONLINE");
#else
  UI_Log("BOOT LOCAL");
#endif
  /* 上电把两个座位的压力判定口径各记一行：Z=空载基线，T=实际生效阈值。
     基线不可信（上电时座位上有人/预压过大/没接）时明确提示，免得"怎么判都不对"。
     ⚠️ 阈值是**每座位独立**的（各自零点+增量），所以必须分两行看。 */
  UI_Log("RST %s", s_rst_cause);        /* 上一次是怎么重启的：POR/PIN/SW/IWDG… */
  for (seat = 0U; seat < SEAT_COUNT; seat++)
  {
    UI_Log("S%u Z%u T%d", (unsigned)SEAT_NO(seat),
           (unsigned)SENSORS_GetZeroSeat(seat),
           SENSORS_GetThresholdSeat(seat));
    if (!SENSORS_IsSeatValid(seat))
    {
      UI_Log("S%u FSR BAD", (unsigned)SEAT_NO(seat));
    }
  }
  UI_Task();          /* 立刻把开机画面刷出来 */

#if OLED_TEST_PATTERN
  /* OLED 自检图案（app_config.h 开关）：用来判断"屏幕怪字符"是显示寻址问题还是没烧进去 */
  UI_ShowTestPattern();
#endif

  /* 阈值由 SENSORS_Init() 算好（空载基线 + 增量），这里不再覆盖。
     ⚠️ 以前这里写死 PRESSURE_THRESHOLD_DEF = 800，会把标定结果顶掉。 */
  g_conn          = ST_DISCONNECTED;
  g_last_poll     = HAL_GetTick();
  g_last_heart    = g_last_poll;
  g_last_ping     = g_last_poll;
  g_last_pong     = g_last_poll;

#if ESP_SELFTEST_ON_BOOT
  /* 上电自检 ESP ↔ STM32 链路：结果直接显示在 OLED（说明见 esp_selftest.h）
     ⚠️ 会阻塞几秒；联调完把 app_config.h 里的 ESP_SELFTEST_ON_BOOT 改回 0 */
  ESP_SelfTest();
#endif

  LOG_Str("[APP] init done\r\n");
}

void SEAT_OnPingResp(void)
{
  g_last_pong = HAL_GetTick();
}

#if APP_AUTOSTART_CONNECT

/* ---------- 连接状态机推进（仅联网模式编译） ---------- */
static void conn_machine(void)
{
  uint32_t now = HAL_GetTick();
  switch (g_conn)
  {
    case ST_DISCONNECTED:
      ESP_ExitTransparent();               /* 尝试退出可能的透传态 */
      if (ESP_ATReady())
      {
        LOG_Str("[APP] ESP AT ready\r\n");
        UI_Log("ESP AT OK");
        g_conn = ST_WIFI;
      }
      else
      {
        LOG_Str("[APP] ESP AT fail\r\n");
        UI_Log("ESP AT FAIL");          /* 去重机制保证重试不会刷屏 */
        UI_Task();                      /* 先把日志/状态刷上屏，再去等这 5 秒 */
        HAL_Delay(RECONNECT_DELAY_MS);     /* 等一会再试 */
      }
      break;

    case ST_WIFI:
      if (ESP_JoinWiFi(WIFI_SSID, WIFI_PASS))
      {
        LOG_Str("[APP] WiFi joined\r\n");
        UI_Log("ESP WIFI OK");
        g_conn = ST_TCP;
      }
      else
      {
        LOG_Str("[APP] WiFi fail, retry\r\n");
        UI_Log("ESP WIFI FAIL");        /* 具体原因看上一行 ESP_JoinWiFi 记的错误码 */

        /* 只在第一次失败时扫一次热点，判断模块到底能不能"看见"这个 AP：
             看不到 ⇒ 基本就是热点是 5GHz / 名称写错 / 距离太远；
             看得到却连不上 ⇒ 密码、加密方式(WPA3)或超时的问题。
           （每次失败都扫会拖慢重试，所以只扫一次） */
        {
          static bool s_scanned = false;
          if (!s_scanned)
          {
            s_scanned = true;
            UI_Log(ESP_ScanForSSID(WIFI_SSID) ? "CWLAP:FOUND" : "CWLAP:NOT FOUND");
          }
        }
        g_conn = ST_DISCONNECTED;
      }
      break;

    case ST_TCP:
      if (ESP_ConnectTCP(ONENET_MQTT_HOST, ONENET_MQTT_PORT))
      {
        LOG_Str("[APP] TCP connected\r\n");
        UI_Log("ESP TCP OK");
        g_conn = ST_TRANSPARENT;
      }
      else
      {
        UI_Log("ESP TCP FAIL");
        g_conn = ST_DISCONNECTED;
      }
      break;

    case ST_TRANSPARENT:
      if (ESP_EnterTransparent())
      {
        LOG_Str("[APP] transparent mode\r\n");
        UI_Log("ESP PASS OK");
        g_conn = ST_MQTT;
      }
      else
      {
        UI_Log("ESP PASS FAIL");
        g_conn = ST_DISCONNECTED;
      }
      break;

    case ST_MQTT:
      if (ONENET_Connect())
      {
        g_last_pong = now;
        UI_Log("ESP MQTT OK");
        g_conn = ST_SUBSCRIBE;
      }
      else
      {
        /* 常见原因：ONENET_DEVICE_TOKEN 是占位符 / 产品未启用 / 网络不通 */
        UI_Log("ESP MQTT FAIL");
        g_conn = ST_DISCONNECTED;
      }
      break;

    case ST_SUBSCRIBE:
      ONENET_SubscribeDownlink();
      g_conn = ST_RUNNING;
      g_last_poll  = now;
      g_last_heart = now;
      g_last_ping  = now;
      g_last_pong  = now;
      /* 刚连上时把去抖快照清掉，让第一包属性上报能如实反映两个座位的当前状态 */
      {
        uint8_t i;
        for (i = 0U; i < SEAT_COUNT; i++)
        {
          g_prev_pir[i]   = false;
          g_prev_alarm[i] = false;
        }
      }
      LOG_Str("[APP] running\r\n");
      UI_Log("ESP SUB OK");
      UI_Log("== ONLINE ==");
      break;

    default:
      break;
  }
}

#endif /* APP_AUTOSTART_CONNECT */

/* ---------- 主处理 ---------- */

void SEAT_App_Process(void)
{
  BUZZER_Task();
  LED_Task();      /* 非阻塞推进：告警红灯 250 ms 翻转 + 标定反馈 300 ms 后恢复 */

#if APP_AUTOSTART_CONNECT
  if (g_conn != ST_RUNNING)
  {
    conn_machine();

    /* ⚠️ 连接期间**也必须刷屏**！
       否则 ESP 的失败日志（WIFI ERR:xx / CWLAP:xx / AT FAIL 等）写进了日志缓冲
       却永远显示不出来 —— 偏偏"连不上"的时候最需要它。
       另外按键切换显示模式也依赖 UI_Task()，所以这里不能省。 */
    UI_Task();

    return;                    /* 连接期间 AT 交互是阻塞的，先不做采集 */
  }

  uint32_t now = HAL_GetTick();

  /* 保活：定期 PINGREQ */
  if ((now - g_last_ping) >= PING_MS)
  {
    ONENET_Ping();
    g_last_ping = now;
  }

  /* 掉线判定：超过 6 分钟没收到过任何下行（含 PINGRESP）→ 重连 */
  if ((now - g_last_pong) > 360000UL)
  {
    LOG_Str("[APP] link lost, reconnect\r\n");
    g_conn = ST_DISCONNECTED;
    return;
  }

  /* 下行报文分发 */
  ONENET_ProcessIncoming();

  /* 平台/路由器把连接关了（模块吐 CLOSED）⇒ 立刻重连，不要等 6 分钟的 pong 超时 */
  if (ONENET_IsLinkLost())
  {
    ONENET_ClearLinkLost();
    LOG_Str("[APP] link closed by peer\r\n");
    UI_Log("LINK CLOSED");
    g_conn = ST_DISCONNECTED;
    return;
  }
#endif

  /* 周期性采集与本地逻辑（本地模式与联网模式都跑） */
  if ((HAL_GetTick() - g_last_poll) >= SENSOR_POLL_MS)
  {
    uint16_t adc[SEAT_COUNT];
    bool     pir[SEAT_COUNT];
    bool     pressed[SEAT_COUNT];
    uint8_t  i;

    g_last_poll = HAL_GetTick();

    /* ---- ① 采集 → ② 融合 → ③ 灯：两个座位各跑一遍，互不影响 ---- */
    for (i = 0U; i < SEAT_COUNT; i++)
    {
      adc[i]     = SENSORS_ReadPressureSeat(i);   /* 采样 + 刷新该路滤波值 */
      pir[i]     = SENSORS_ReadPIRSeat(i);
      pressed[i] = SENSORS_IsPressedSeat(i);      /* 用这一路自己的阈值 */

      FUSION_Update(i, pressed[i], pir[i]);

      g_last_pressure[i] = adc[i];
      g_last_pir[i]      = pir[i];
      g_last_alarm[i]    = FUSION_GetAlarm(i);

      /* 灯态跟随融合状态：绿=空闲 / 黄=使用中 / 红闪=告警（三态互斥，非阻塞） */
      LED_SetSeatState(i, (uint8_t)FUSION_GetState(i));

      /* ---- 把各模块的变化记进事件日志（按 K1 切到 LOG 页即可看到）---- */
      if (pressed[i] != g_prev_pressed[i])
      {
        UI_Log("S%u FSR %s%u", (unsigned)SEAT_NO(i),
               pressed[i] ? ">" : "<", (unsigned)adc[i]);
      }
      if (pir[i] != g_prev_pir[i])
      {
        UI_Log("S%u PIR %u", (unsigned)SEAT_NO(i), (unsigned)(pir[i] ? 1U : 0U));
      }

      /* 新出现的假占座告警：**只有红闪 + OLED**，蜂鸣器不参与告警（docs/05 §3.3） */
      if (g_last_alarm[i] && !g_prev_alarm[i])
      {
        UI_Log("S%u ALARM", (unsigned)SEAT_NO(i));
      }
    }

#if APP_AUTOSTART_CONNECT
    /* ---- ④ 汇总上报：任一座位有变化就整包发，否则按 60 s 心跳 ---- */
    {
      bool changed = false;

      for (i = 0U; i < SEAT_COUNT; i++)
      {
        if (abs((int)adc[i] - (int)g_prev_pressure[i]) > 50) changed = true;
        if (pir[i] != g_prev_pir[i])                        changed = true;
        if (g_last_alarm[i] != g_prev_alarm[i])             changed = true;
      }

      if (changed || ((g_last_poll - g_last_heart) >= HEARTBEAT_MS))
      {
        ONENET_PublishProperty(adc, pir, g_last_alarm);
        UI_Log("PUB %u/%u", (unsigned)adc[0], (unsigned)adc[1]);
        g_last_heart = g_last_poll;
      }

      /* 告警上升沿：**每个座位独立计**，事件里带 seat_no（后端按座位落库） */
      for (i = 0U; i < SEAT_COUNT; i++)
      {
        if (g_last_alarm[i] && !g_prev_alarm[i])
        {
          ONENET_PublishFakeOccupy(SEAT_NO(i), adc[i], pir[i]);
          UI_Log("EVT FAKE S%u", (unsigned)SEAT_NO(i));
        }
      }
    }
#endif

    for (i = 0U; i < SEAT_COUNT; i++)
    {
      g_prev_pressed[i]  = pressed[i];
      g_prev_pressure[i] = adc[i];
      g_prev_pir[i]      = pir[i];
      g_prev_alarm[i]    = g_last_alarm[i];

      /* 把实时值交给显示层；真正的刷屏在函数末尾那次 UI_Task() 里节流完成 */
      UI_SetSeatStatus(i, adc[i], (uint8_t)(pir[i] ? 1U : 0U),
                       (uint8_t)FUSION_GetState(i));
    }

    /* 最近刷到的卡（设备级，不分座位） */
    UI_SetUID(g_uid_line);

    /* RFID 刷卡（去重：同一张卡 5 秒内只处理一次） */
    if (MFRC522_IsCardPresent())
    {
      uint8_t uid[4];
      uint8_t uid_len = 0;
      if (MFRC522_ReadUID(uid, &uid_len) && uid_len == 4)
      {
        bool same = (memcmp(uid, g_last_uid, 4) == 0);
        if (!same || ((HAL_GetTick() - g_last_uid_time) > RFID_READ_INTERVAL_MS))
        {
          memcpy(g_last_uid, uid, 4);
          g_last_uid_time = HAL_GetTick();

          /* 蜂鸣器只做「换新卡」的刷卡反馈（docs/05 §3.3）：
             同一张卡放着不动就算 5 s 后重发事件，也**不再响**。 */
          if (!same)
          {
            BUZZER_Beep(BUZZER_CARD_MS);
          }

          (void)snprintf(g_uid_line, sizeof(g_uid_line), "UID %02X%02X%02X%02X",
                         uid[0], uid[1], uid[2], uid[3]);
          UI_Log("RFID %02X%02X%02X%02X", uid[0], uid[1], uid[2], uid[3]);
#if APP_AUTOSTART_CONNECT
          {
            char hex[9];
            (void)snprintf(hex, sizeof(hex), "%02X%02X%02X%02X",
                           uid[0], uid[1], uid[2], uid[3]);
            ONENET_PublishRfid(hex);
            UI_Log("EVT RFID");
          }
#endif
        }
      }
    }
  }

  /* 🔴 按键扫描必须**每轮主循环**都做（ms 级），不能塞在上面那个 1 秒的采集块里：
     否则一次 100~300 ms 的正常按键根本采不到，现象就是"按键按下没反应"
     （旧代码只在 1 Hz 的采集块里调 UI_Task()，等于要求你按住 2 秒以上才生效）。
     刷屏速度不受影响 —— UI_Task() 内部有 500 ms 节流，按键逻辑本身只读两个 GPIO。 */
  UI_Task();
}

/* ---------- 下行回调 ---------- */

void SEAT_OnPropertySet(const char *json)
{
  int v;
  if (JSON_GetNestedInt(json, "seat_display", &v))
  {
    if (v >= 0 && v <= 4) g_seat_display = (uint8_t)v;
  }
  if (JSON_GetNestedInt(json, "adc_threshold", &v))
  {
    /* 云端下发的是**绝对** ADC 阈值（0~4095），直接覆盖本地"基线+增量"的结果 */
    SENSORS_SetThreshold(v);
    LOG_Str("[DOWN] adc_threshold updated\r\n");
    UI_Log("DOWN THR=%d", v);
  }
}

void SEAT_OnServiceInvoke(const char *service_id, const char *json)
{
  if (strcmp(service_id, "buzzer_ctrl") == 0)
  {
    int d = 0;
    if (JSON_GetDirectInt(json, "duration_ms", &d))
    {
      BUZZER_Beep((uint32_t)d);
      UI_Log("DOWN BUZZ %d", d);
    }
  }
  /* 兼容分支：早期物模型里有过 force_release，当前物模型只剩 buzzer_ctrl。
     留着不会触发，万一云端旧配置下发也能正常处理。
     ⚠️ V17 双座位：告警是**每座位一份**的，所以这里必须两个座位都复位，
        不能像单座位时代那样只清一个 g_last_alarm。 */
  else if (strcmp(service_id, "force_release") == 0)
  {
    uint8_t i;
    g_seat_display = 0;
    for (i = 0U; i < SEAT_COUNT; i++)
    {
      FUSION_Reset(i);
      g_last_alarm[i] = false;
    }
    LOG_Str("[DOWN] force_release\r\n");
    UI_Log("DOWN RELEASE");
  }
}
