/**
  ******************************************************************************
  * @file    ui.c
  * @brief   OLED 统一显示层实现（V17：两座位状态面板 + 日志 + 标定页）
  *
  * @note    页面布局（每行最多 20 个字符，128px / 6px 字宽）：
  *
  *   ① 状态页（默认）
  *        SEAT_001 V17 [S1]        ← 设备名 / 固件版本 / 当前座位（切座位后闪两次）
  *        S1 IDLE  PIR0 P:12       ← 座位1：状态 / 红外 / 压力
  *        S2 USED  PIR1 P:1204     ← 座位2：同上（`PIR1!` 末尾的 ! = PIR 可疑）
  *        UID A1B2C3D4             ← 最近刷到的卡（没刷过显示 SWIPE CARD）
  *        ESP OK 115200            ← ESP 自检结论 + 波特率
  *        RX:1234 DR:0             ← ESP 串口收字节 / 丢字节
  *        RF:92 A:3C E:0           ← RC522 版本号 / OLED 地址 / I2C 错误数
  *        V:1480 K1:PG K2:CAL      ← VREFINT 自检 + 按键提示
  *
  *   ② 日志页：最近 7 条事件滚动显示（最新在最后一行）
  *
  *   ③ 标定页（K2 短按只标「当前座位」，所以这一页**只显示当前座位**）
  *        FSR [S1] K1:NEXT         ← 标题带当前座位，K1 短按翻页
  *        THR: 120 Z:   3          ← 该座位生效阈值 / 空载基线（-1 = 该路不可信）
  *        RAW: 1234 FLT: 1230      ← 原始值 / 滤波值（真正参与判定的）
  *        MN:    0 MX: 4095        ← 上电以来最小 / 最大值（定 PRESSURE_DELTA_DEF 看这里）
  *        PIR:0 SEAT EMPTY         ← 该座位红外 / 判定结果（FSR NOT VALID = 没接/悬空）
  *        V: 1480                  ← VREFINT（正常约 1480）
  *        K2:CALS1 K2L:ALL         ← K2 短按标 S1；K2 长按 = 全部标定
  *        CLEAR SEATS K2=3s        ← 只有"全标定待确认"时才出现（3 s 倒计时）
  ******************************************************************************
  */

#include "ui.h"
#include "oled_ssd1306.h"
#include "esp8266.h"
#include "mfrc522.h"
#include "esp_selftest.h"
#include "fsr.h"            /* 标定页要显示 RAW/FLT/MIN/MAX 与 VREFINT */
#include "sensors.h"        /* 该座位的阈值 / 零点 / 有效性 */
#include "fusion.h"         /* 该座位状态、PIR 是否可疑 */
#include "led.h"            /* 标定成功的 6 灯全亮反馈 */
#include "app_config.h"
#include "main.h"

#include <stdarg.h>
#include <stdio.h>
#include <string.h>

/* ------------------------------ 参数 ------------------------------ */

#define UI_LOG_LINES        7      /* 日志保留条数（正好占满屏幕第 1~7 行） */
#define UI_LOG_LEN          23     /* 每条最长字符数（含结尾 0） */
#define UI_REFRESH_MS       500    /* 刷屏节流：最快 500 ms 一次（整屏刷新约 90 ms） */
#define UI_BLINK_REFRESH_MS 250    /* 切座位闪烁期间临时加快，否则看不出闪两次 */

/* ------------------------------ 状态 ------------------------------ */

static char       s_log[UI_LOG_LINES][UI_LOG_LEN];
static uint8_t    s_head   = 0;        /* 下一条写入位置 */
static uint8_t    s_filled = 0;        /* 已记录条数 */
static volatile bool s_dirty = true;   /* 需要刷屏 */

static ui_mode_t  s_mode = UI_MODE_STATUS;

/* 两个座位的实时值（由 seat_app 通过 UI_SetSeatStatus 更新） */
static uint16_t   s_pressure[SEAT_COUNT];
static uint8_t    s_pir[SEAT_COUNT];
static uint8_t    s_state[SEAT_COUNT];   /* 0 空闲 1 使用 2 假占座 */

/* 最近刷到的卡（设备级） */
static char       s_uid[UI_LOG_LEN] = "";

/* 当前座位：标定页与 K2 短按作用的对象（K1 长按切换） */
static uint8_t    s_cur_seat = 0;

/* 切座位后标题 [S1] 闪烁两次 */
static uint32_t   s_seat_blink_start = 0;
static uint32_t   s_seat_blink_until = 0;

/* 「全部标定」待确认（K2 长按进入，3 s 内再短按一次才执行） */
static bool       s_cal_pending  = false;
static uint32_t   s_cal_deadline = 0;

/* 按键状态机（K1=PA2 / K2=PA3，低电平按下） */
typedef struct
{
  uint8_t  raw;         /* 最近一次读到的原始电平 */
  uint8_t  stable;      /* 消抖后的电平 */
  uint32_t t_change;    /* 原始电平变化时刻（消抖用） */
  uint32_t press_t;     /* 消抖后的按下时刻（长短按判定用） */
  bool     long_done;   /* 本次按压的长按动作是否已执行 */
} ui_key_t;

static ui_key_t   s_k1;
static ui_key_t   s_k2;

/* 刷屏计时 */
static uint32_t   s_last_refresh = 0;

volatile uint32_t g_ui_refresh_cnt = 0;
volatile uint32_t g_ui_log_cnt = 0;

/* ---------------------------- 内部函数 ---------------------------- */

/** 把最近 s_filled 条日志画到屏幕（最新在最后一行） */
static void render_log(void)
{
  uint8_t i;
  uint8_t start;

  OLED_Clear();
  OLED_DrawString(0, 0, "LOG  K1:NEXT");

  /* s_head 指向"下一条要写的位置"，所以最早的一条在 s_head（若已填满） */
  start = (s_filled < UI_LOG_LINES) ? 0U : s_head;

  for (i = 0; i < UI_LOG_LINES; i++)
  {
    if (i >= s_filled)
    {
      break;
    }
    OLED_DrawString(0, (uint8_t)(i + 1U), s_log[(start + i) % UI_LOG_LINES]);
  }
  OLED_Refresh();
}

/**
 * @brief 标定页：**只画当前座位**（K2 短按标的也是它，屏幕上必须有上下文）
 * @note  用 FSR_ReadRaw 取实时原始值（不影响判定用的滤波窗口）
 */
static void render_fsr(void)
{
  char     l[24];
  uint8_t  seat = s_cur_seat;
  uint8_t  ch   = SENSORS_SeatChannel(seat);   /* 通道号统一问 sensors.c，别自己写 seat */
  uint16_t mn   = (g_fsr_min[ch] == 0xFFFFU) ? 0U : g_fsr_min[ch];
  int      thr  = SENSORS_GetThresholdSeat(seat);

  OLED_Clear();

  /* 第 0 行：标题带当前座位 —— 一眼确认"现在标的是谁" */
  (void)snprintf(l, sizeof(l), "FSR [S%u] K1:NEXT", (unsigned)SEAT_NO(seat));
  OLED_DrawString(0, 0, l);

  /* 第 1 行：该座位生效阈值 / 空载基线；THR 为 -1 表示该路不可信（没接/悬空） */
  (void)snprintf(l, sizeof(l), "THR:%4d Z:%4u", thr,
                 (unsigned)SENSORS_GetZeroSeat(seat));
  OLED_DrawString(0, 1, l);

  /* 第 2 行：原始值 / 滤波值（真正参与"有人/无人"判定的是滤波值） */
  (void)snprintf(l, sizeof(l), "RAW:%4u FLT:%4u",
                 (unsigned)FSR_ReadRaw(ch), (unsigned)g_fsr_filtered[ch]);
  OLED_DrawString(0, 2, l);

  /* 第 3 行：上电以来的最小 / 最大值 —— 定 PRESSURE_DELTA_DEF 就看这两个数 */
  (void)snprintf(l, sizeof(l), "MN:%4u MX:%4u", (unsigned)mn, (unsigned)g_fsr_max[ch]);
  OLED_DrawString(0, 3, l);

  /* 第 4 行：该座位红外 + 判定结果 */
  if (!SENSORS_IsSeatValid(seat))
  {
    (void)snprintf(l, sizeof(l), "PIR:%u FSR NOT VALID",
                   (unsigned)(SENSORS_ReadPIRSeat(seat) ? 1U : 0U));
  }
  else
  {
    (void)snprintf(l, sizeof(l), "PIR:%u SEAT %s",
                   (unsigned)(SENSORS_ReadPIRSeat(seat) ? 1U : 0U),
                   SENSORS_IsPressedSeat(seat) ? "USED" : "EMPTY");
  }
  OLED_DrawString(0, 4, l);

  /* 第 5 行：VREFINT 自检（正常约 1480 ⇒ ADC 本身正常） */
  (void)snprintf(l, sizeof(l), "V:%4u", (unsigned)g_fsr_vref);
  OLED_DrawString(0, 5, l);

  /* 第 6 行：按键提示 */
  (void)snprintf(l, sizeof(l), "K2:CALS%u K2L:ALL", (unsigned)SEAT_NO(seat));
  OLED_DrawString(0, 6, l);

  /* 第 7 行：全标定待确认时给出倒计时（平时留空） */
  if (s_cal_pending)
  {
    uint32_t now  = HAL_GetTick();
    uint32_t left = ((int32_t)(s_cal_deadline - now) > 0)
                  ? ((s_cal_deadline - now + 999U) / 1000U) : 0U;
    (void)snprintf(l, sizeof(l), "CLEAR SEATS K2=%us", (unsigned)left);
    OLED_DrawString(0, 7, l);
  }

  OLED_Refresh();
}

/** 状态页：两个座位各一行 + 各模块"通信健康度" */
static void render_status(void)
{
  char     l[24];
  uint32_t now = HAL_GetTick();
  uint8_t  seat;

  OLED_Clear();

  /* 第 0 行：设备名 + 固件版本 + 当前座位标记。
     切座位后 [S1] 会闪两次（亮/灭交替各 250 ms），所以这里按时间算"这一帧亮不亮"。
     看到 `SEAT_001 V17 [S1]` 才说明新固件真的烧进去了（旧版没有版本号也没有 [S1]）。 */
  {
    char mark[6];
    bool on = true;

    if ((int32_t)(s_seat_blink_until - now) > 0)
    {
      on = ((((now - s_seat_blink_start) / KEY_SEAT_BLINK_MS) & 1U) == 0U);
    }
    (void)snprintf(mark, sizeof(mark), on ? "[S%u]" : " S%u ", (unsigned)SEAT_NO(s_cur_seat));
    (void)snprintf(l, sizeof(l), "%s %s %s", DEVICE_NAME, FW_VERSION, mark);
    OLED_DrawString(0, 0, l);
  }

  /* 第 1/2 行：两个座位各一行 —— 状态 / 红外 / 压力
     末尾 `!` = PIR 可疑：连续 PIR_STUCK_MS 都是"没压力却一直高"
     ⇒ 多半是模块接反 / 型号不对（AM312 用 5V）/ 坏了 / 前方有持续热源。 */
  for (seat = 0U; seat < SEAT_COUNT; seat++)
  {
    const char *st = (s_state[seat] == 1U) ? "USED"
                   : (s_state[seat] == 2U) ? "FAKE" : "IDLE";

    (void)snprintf(l, sizeof(l), "S%u %s PIR%u%c P:%u",
                   (unsigned)SEAT_NO(seat), st,
                   (unsigned)s_pir[seat],
                   FUSION_IsPirSuspect(seat) ? '!' : ' ',
                   (unsigned)s_pressure[seat]);
    OLED_DrawString(0, (uint8_t)(1U + seat), l);
  }

  /* 第 3 行：最近刷到的卡（RC522） */
  OLED_DrawString(0, 3, (s_uid[0] != 0) ? s_uid : "SWIPE CARD");

  /* 第 4 行：ESP 自检结论 + 波特率 */
  switch (g_esp_test_result)
  {
    case ESP_TEST_OK:
      (void)snprintf(l, sizeof(l), "ESP OK %lu", (unsigned long)g_esp_test_baud);
      break;
    case ESP_TEST_FAIL_NO_AT:
      (void)snprintf(l, sizeof(l), "ESP NO AT");
      break;
    case ESP_TEST_FAIL_NO_ECHO:
      (void)snprintf(l, sizeof(l), "ESP LOOP FAIL");
      break;
    default:
      (void)snprintf(l, sizeof(l), "ESP ?");
      break;
  }
  OLED_DrawString(0, 4, l);

  /* 第 5 行：ESP 串口收字节 / 丢字节（丢字节应恒为 0） */
  (void)snprintf(l, sizeof(l), "RX:%lu DR:%lu",
                 (unsigned long)g_esp_rx_cnt, (unsigned long)g_esp_rx_drop);
  OLED_DrawString(0, 5, l);

  /* 第 6 行：RC522 版本号（真片 0x91/0x92）+ OLED 实际地址与 I2C 错误数 */
  (void)snprintf(l, sizeof(l), "RF:%02X A:%02X E:%lu",
                 (unsigned)g_mfrc522_version, (unsigned)g_oled_i2c_addr,
                 (unsigned long)g_oled_i2c_err);
  OLED_DrawString(0, 6, l);

  /* 第 7 行：VREFINT 自检值（**正常约 1480** ⇒ ADC 正常）+ 按键提示 */
  (void)snprintf(l, sizeof(l), "V:%4u K1:PG K2:CAL", (unsigned)g_fsr_vref);
  OLED_DrawString(0, 7, l);

  OLED_Refresh();
}

/* --------------------------- 按键动作 --------------------------- */

/** K1 短按：翻页（STATUS → LOG → FSR → STATUS） */
static void ui_next_mode(void)
{
  switch (s_mode)
  {
    case UI_MODE_STATUS: UI_SetMode(UI_MODE_LOG);   break;
    case UI_MODE_LOG:    UI_SetMode(UI_MODE_FSR);    break;
    default:             UI_SetMode(UI_MODE_STATUS); break;
  }
}

/** K1 长按：切换当前座位，标题 [S1] 闪两次 */
static void ui_toggle_seat(void)
{
  uint32_t now = HAL_GetTick();

  s_cur_seat = (uint8_t)((s_cur_seat + 1U) % SEAT_COUNT);

  s_seat_blink_start = now;
  s_seat_blink_until = now + (4U * KEY_SEAT_BLINK_MS);   /* 亮灭亮灭 = 闪两次 */

  UI_Log("SEAT -> S%u", (unsigned)SEAT_NO(s_cur_seat));
  s_dirty = true;
}

/** K2 短按：标定当前座位的空载零点 */
static void ui_cal_seat(void)
{
  uint8_t seat = s_cur_seat;

  /* 座位上还有人/东西时标零，会把"有人"学成零点 ⇒ 之后再也判不出有人 */
  if (SENSORS_IsPressedSeat(seat))
  {
    UI_Log("S%u BUSY NO CAL", (unsigned)SEAT_NO(seat));
    s_dirty = true;
    return;
  }

  SENSORS_RecalibrateSeat(seat);
  LED_CalFlash();                                    /* 6 灯全亮 0.3 s 作反馈 */
  UI_Log("CAL S%u OK Z%u", (unsigned)SEAT_NO(seat),
         (unsigned)SENSORS_GetZeroSeat(seat));
  s_dirty = true;
}

/** K2 长按 + 确认：两个座位一起标定（要求两个座位都没人） */
static void ui_cal_all(void)
{
  uint8_t seat;

  for (seat = 0U; seat < SEAT_COUNT; seat++)
  {
    if (SENSORS_IsPressedSeat(seat))
    {
      UI_Log("CAL ABORT S%u BUSY", (unsigned)SEAT_NO(seat));
      s_dirty = true;
      return;
    }
  }

  SENSORS_Recalibrate();
  LED_CalFlash();
  UI_Log("CAL ALL OK");
  s_dirty = true;
}

/* ------------------------------ 接口 ------------------------------ */

void UI_Init(void)
{
  uint8_t i;

  for (i = 0; i < UI_LOG_LINES; i++)
  {
    s_log[i][0] = 0;
  }
  s_head = 0;
  s_filled = 0;
  s_dirty = true;
  s_mode = UI_MODE_STATUS;

  for (i = 0; i < SEAT_COUNT; i++)
  {
    s_pressure[i] = 0;
    s_pir[i] = 0;
    s_state[i] = 0;
  }

  s_uid[0] = 0;
  s_cur_seat = 0;

  s_seat_blink_start = 0;
  s_seat_blink_until = 0;
  s_cal_pending = false;
  s_cal_deadline = 0;

  /* 按键初值一律当"松开"（PA2/PA3 上拉，松开=高=1） */
  s_k1.raw = 1U; s_k1.stable = 1U; s_k1.t_change = 0U; s_k1.press_t = 0U; s_k1.long_done = false;
  s_k2.raw = 1U; s_k2.stable = 1U; s_k2.t_change = 0U; s_k2.press_t = 0U; s_k2.long_done = false;

  g_ui_refresh_cnt = 0;
  g_ui_log_cnt = 0;
  s_last_refresh = 0;
}

void UI_SetMode(ui_mode_t m)
{
  if (m != s_mode)
  {
    s_mode = m;
    s_dirty = true;
  }
}

ui_mode_t UI_GetMode(void)
{
  return s_mode;
}

uint8_t UI_GetCurSeat(void)
{
  return s_cur_seat;
}

void UI_Log(const char *fmt, ...)
{
  char buf[UI_LOG_LEN];
  va_list ap;

  if (fmt == NULL)
  {
    return;
  }

  va_start(ap, fmt);
  (void)vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);

  /* 去重：与上一条完全相同就丢弃 —— 否则"连接失败每 5 秒重试一次"会把 7 行日志全刷满 */
  if (s_filled > 0U)
  {
    uint8_t prev = (uint8_t)((s_head + UI_LOG_LINES - 1U) % UI_LOG_LINES);
    if (strcmp(s_log[prev], buf) == 0)
    {
      s_dirty = true;
      return;
    }
  }

  (void)strncpy(s_log[s_head], buf, UI_LOG_LEN - 1U);
  s_log[s_head][UI_LOG_LEN - 1U] = 0;

  s_head = (uint8_t)((s_head + 1U) % UI_LOG_LINES);
  if (s_filled < UI_LOG_LINES)
  {
    s_filled++;
  }
  g_ui_log_cnt++;
  s_dirty = true;
}

void UI_SetSeatStatus(uint8_t seat, uint16_t pressure, uint8_t pir, uint8_t seat_state)
{
  if (seat >= SEAT_COUNT)
  {
    return;
  }

  if ((s_pressure[seat] != pressure) || (s_pir[seat] != pir) || (s_state[seat] != seat_state))
  {
    s_dirty = true;
  }
  s_pressure[seat] = pressure;
  s_pir[seat]      = pir;
  s_state[seat]    = seat_state;
}

void UI_SetUID(const char *uid)
{
  if (uid == NULL)
  {
    return;
  }

  if (strncmp(uid, s_uid, UI_LOG_LEN - 1U) != 0)
  {
    (void)strncpy(s_uid, uid, UI_LOG_LEN - 1U);
    s_uid[UI_LOG_LEN - 1U] = 0;
    s_dirty = true;
  }
}

void UI_ShowTestPattern(void)
{
  uint8_t p;

  /* ============ ① 逐页扫描（最关键的一步）============
     每次只点亮一页（8 行像素，=128 字节 0xFF），从 page 0 走到 page 7，
     每步停 400ms。屏幕上应该出现**一条横线，从上往下匀速走 8 格**。

       · 横线"从最上面开始、往下走、走到最下面"  ⇒ 寻址与方向都正确 ✓
       · 横线从中间开始 / 只走几格 / 越走越往上  ⇒ 寻址指令(0x21/0x22)
         或 COM 扫描方向(0xC8) 与该模块不匹配
       · 完全看不到横线                        ⇒ 写入根本没生效（接线/地址） */
  for (p = 0U; p < 8U; p++)
  {
    OLED_Fill(0x00);
    OLED_SetPagePattern(p, 0xFF);
    OLED_Refresh();
    HAL_Delay(400);
  }

  /* ============ ② 全亮 → 全灭 ============
     验证 1024 字节是否真的覆盖整屏。
     若"全亮"不是整屏白（只有一条/半屏/花屏），说明该模块的显存布局与 128x64 不符
     （例如实际是 128x32，或控制器是 SH1106 而非 SSD1306）。 */
  OLED_Fill(0xFF);
  OLED_Refresh();
  HAL_Delay(1200);

  OLED_Fill(0x00);
  OLED_Refresh();
  HAL_Delay(400);

  /* ============ ③ 三行字 ============
     顶部应是设备名、中间 MIDDLE、底部 BOTTOM。
     若上下颠倒 ⇒ 0xC8 / 0xC0 要互换（COM 扫描方向相反）。 */
  OLED_Clear();
  OLED_DrawString(0, 0, DEVICE_NAME);
  OLED_DrawString(0, 3, "MIDDLE ROW 3");
  OLED_DrawString(0, 7, "BOTTOM ROW 7");
  OLED_Refresh();

  /* 之后停在这个画面（UI_Task 在自检模式下不会覆盖它），方便拍照对比。 */
}

void UI_Task(void)
{
  uint32_t now = HAL_GetTick();

#if OLED_TEST_PATTERN
  /* 自检模式下屏幕由 UI_ShowTestPattern() 独占 —— 主循环不要覆盖它，
     否则自检画面会被状态面板刷掉，人眼看不清。 */
  (void)now;
  return;
#endif

  /* ---------------- K1（PA2）：短按翻页 / 长按切当前座位 ----------------
     状态机（docs/05 §4.1）：
       按下记录时刻；松开时 < 600 ms ⇒ 翻页；
       按住 ≥ 1000 ms（**未松开**）⇒ 切座位，并置"已处理"，松开时不再翻页。 */
  {
    uint8_t raw = (HAL_GPIO_ReadPin(BUT1_IN_GPIO_Port, BUT1_IN_Pin) == GPIO_PIN_RESET) ? 0U : 1U;

    if (raw != s_k1.raw)
    {
      s_k1.raw = raw;
      s_k1.t_change = now;
    }

    if (((now - s_k1.t_change) >= KEY_DEBOUNCE_MS) && (s_k1.stable != raw))
    {
      s_k1.stable = raw;
      if (raw == 0U)
      {
        s_k1.press_t   = now;         /* 消抖后的按下时刻 */
        s_k1.long_done = false;
      }
      else if (!s_k1.long_done && ((now - s_k1.press_t) < KEY_SHORT_MS))
      {
        ui_next_mode();               /* 短按：翻页 */
      }
    }

    if ((s_k1.stable == 0U) && !s_k1.long_done &&
        ((now - s_k1.press_t) >= KEY_K1_LONG_MS))
    {
      s_k1.long_done = true;
      ui_toggle_seat();               /* 长按：切当前座位（不等松开） */
    }
  }

  /* ---------------- K2（PA3）：短按标当前座位 / 长按全标定 ----------------
       松开时 < 600 ms ⇒ 标当前座位（若正处于"待确认"则执行全部标定）；
       按住 ≥ 2000 ms（**未松开**）⇒ 进入"全部标定待确认"，3 s 内再短按一次才执行。 */
  {
    uint8_t raw = (HAL_GPIO_ReadPin(BUT2_IN_GPIO_Port, BUT2_IN_Pin) == GPIO_PIN_RESET) ? 0U : 1U;

    if (raw != s_k2.raw)
    {
      s_k2.raw = raw;
      s_k2.t_change = now;
    }

    if (((now - s_k2.t_change) >= KEY_DEBOUNCE_MS) && (s_k2.stable != raw))
    {
      s_k2.stable = raw;
      if (raw == 0U)
      {
        s_k2.press_t   = now;
        s_k2.long_done = false;
      }
      else if (!s_k2.long_done && ((now - s_k2.press_t) < KEY_SHORT_MS))
      {
        if (s_cal_pending)
        {
          s_cal_pending = false;      /* 确认：两个座位一起标定 */
          ui_cal_all();
        }
        else
        {
          ui_cal_seat();              /* 普通短按：标当前座位 */
        }
      }
    }

    if ((s_k2.stable == 0U) && !s_k2.long_done &&
        ((now - s_k2.press_t) >= KEY_K2_LONG_MS))
    {
      s_k2.long_done = true;
      if (!s_cal_pending)
      {
        s_cal_pending  = true;
        s_cal_deadline = now + KEY_CAL_CONFIRM_MS;
        UI_SetMode(UI_MODE_FSR);      /* 自动切到标定页，让提示真的看得见 */
        UI_Log("CLEAR BOTH SEATS");
        s_dirty = true;
      }
    }
  }

  /* 待确认超时 ⇒ 取消（防"按了长按忘了确认"，之后误触短按把两路零点一起冲掉） */
  if (s_cal_pending && ((int32_t)(now - s_cal_deadline) >= 0))
  {
    s_cal_pending = false;
    UI_Log("CAL CANCEL");
    s_dirty = true;
  }

  /* ---- 节流刷屏 ---- */
  {
    uint32_t interval = UI_REFRESH_MS;

    /* 切座位的闪烁期间必须加快刷新，否则 500 ms 节流下"闪两次"看不出来 */
    if ((s_mode == UI_MODE_STATUS) && ((int32_t)(s_seat_blink_until - now) > 0))
    {
      interval = UI_BLINK_REFRESH_MS;
    }

    if (s_dirty && ((now - s_last_refresh) >= interval))
    {
      s_dirty = false;
      s_last_refresh = now;
      g_ui_refresh_cnt++;

      if (s_mode == UI_MODE_LOG)
      {
        render_log();
      }
      else if (s_mode == UI_MODE_FSR)
      {
        render_fsr();
      }
      else
      {
        render_status();
      }
    }
  }
}
