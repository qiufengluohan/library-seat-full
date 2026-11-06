/**
  ******************************************************************************
  * @file    led.c
  * @brief   每座位三色灯实现（三态互斥 + 非阻塞闪烁）
  *
  * @note    设计要点（docs/05-双座位改造方案.md §3.1）：
  *          ① 一个座位同时只可能亮**一种**颜色：
  *               SEAT_IDLE        → 绿亮
  *               SEAT_OCCUPIED    → 黄亮
  *               SEAT_FAKE_OCCUPY → 红 **250 ms 翻转**（2 Hz）
  *             所以切换状态时要把另外两只显式关掉，不能只点亮目标那只。
  *          ② 闪烁靠 **HAL_GetTick() 比较**推进，不用 HAL_Delay ——
  *             主循环还要跑按键扫描、MQTT 收发、OLED 刷屏。
  *          ③ 标定成功要"6 灯全亮 300 ms"作反馈，期间压制正常灯态，
  *             到点后把每个座位**按它当前的状态**重新画一遍（不是简单全灭）。
  ******************************************************************************
  */

#include "led.h"
#include "app_config.h"
#include "fusion.h"
#include "main.h"

/* 一个座位上的三只灯的下标（**逻辑颜色顺序固定：绿 / 黄 / 红**） */
#define LED_GREEN   0U
#define LED_YELLOW  1U
#define LED_RED     2U

typedef struct
{
  GPIO_TypeDef *port;
  uint16_t      pin;
} led_pin_t;

/**
 * @brief 座位 → 三只灯的引脚表（按**实物装配**填，不按 LED 编号顺序）
 *
 * @note  🔴 2026-10-29 按实物改正：板子上焊的是
 *          红 = LED1 / LED4、绿 = LED2 / LED5、黄 = LED3 / LED6
 *        （和原理图丝印的编号顺序不是"绿黄红"，之前按编号顺序填导致
 *          "有人坐亮绿灯、假占座亮黄灯、红灯不亮"）。
 *
 *        颜色 → LED 编号 → 引脚（来自 main.h，最终来自 2026-10-20 原理图）：
 *          座位 1：绿 LED2 = PA5，黄 LED3 = PA4，红 LED1 = PB0
 *          座位 2：绿 LED5 = PA9，黄 LED6 = PA10，红 LED4 = PA8
 *
 *        ⚠️ 以后若重新焊接/改板，只改这张表 + led.h 的文档，别去动状态机。
 */
static const led_pin_t s_led[SEAT_COUNT][3] =
{
  /* 座位 1：绿(PA5)          黄(PA4)          红(PB0) */
  { {LED2_GPIO_Port, LED2_Pin}, {LED3_GPIO_Port, LED3_Pin}, {LED1_GPIO_Port, LED1_Pin} },
  /* 座位 2：绿(PA9)          黄(PA10)         红(PA8) */
  { {LED5_GPIO_Port, LED5_Pin}, {LED6_GPIO_Port, LED6_Pin}, {LED4_GPIO_Port, LED4_Pin} }
};

/** 每个座位当前的"视觉状态"（三态互斥） */
typedef enum
{
  LED_VIS_OFF = 0,        /* 未初始化 / 强制全灭 */
  LED_VIS_GREEN,          /* 空闲 */
  LED_VIS_YELLOW,         /* 使用中 */
  LED_VIS_RED_BLINK       /* 假占座告警：红灯闪 */
} led_vis_t;

static led_vis_t s_vis[SEAT_COUNT];
static bool      s_red_on[SEAT_COUNT];      /* 红灯当前是亮还是灭（闪烁用） */
static uint32_t  s_blink_next[SEAT_COUNT];  /* 下一次翻转的时刻 */

/* 标定成功反馈（6 灯全亮一小段时间） */
static bool      s_flash_active = false;
static uint32_t  s_flash_until  = 0U;

/* ---------------------------- 内部函数 ---------------------------- */

static void led_write(const led_pin_t *p, bool on)
{
  /* 高电平点亮：原理图是 引脚 → 330Ω → LED → GND */
  HAL_GPIO_WritePin(p->port, p->pin, on ? GPIO_PIN_SET : GPIO_PIN_RESET);
}

static void led_seat_off(uint8_t seat)
{
  uint8_t i;

  for (i = 0U; i < 3U; i++)
  {
    led_write(&s_led[seat][i], false);
  }
}

/** 按当前视觉状态输出三只灯 —— 三态互斥的关键：目标亮，另外两只必须灭 */
static void led_seat_apply(uint8_t seat)
{
  switch (s_vis[seat])
  {
    case LED_VIS_GREEN:
      led_write(&s_led[seat][LED_GREEN],  true);
      led_write(&s_led[seat][LED_YELLOW], false);
      led_write(&s_led[seat][LED_RED],    false);
      break;

    case LED_VIS_YELLOW:
      led_write(&s_led[seat][LED_GREEN],  false);
      led_write(&s_led[seat][LED_YELLOW], true);
      led_write(&s_led[seat][LED_RED],    false);
      break;

    case LED_VIS_RED_BLINK:
      led_write(&s_led[seat][LED_GREEN],  false);
      led_write(&s_led[seat][LED_YELLOW], false);
      led_write(&s_led[seat][LED_RED],    s_red_on[seat]);
      break;

    case LED_VIS_OFF:
    default:
      led_seat_off(seat);
      break;
  }
}

/* ------------------------------ 接口 ------------------------------ */

void LED_Init(void)
{
  uint8_t seat;

  for (seat = 0U; seat < SEAT_COUNT; seat++)
  {
    s_vis[seat]        = LED_VIS_OFF;
    s_red_on[seat]     = false;
    s_blink_next[seat] = 0U;
    led_seat_off(seat);
  }

  s_flash_active = false;
  s_flash_until  = 0U;
}

void LED_SetSeatState(uint8_t seat, uint8_t seat_state)
{
  led_vis_t vis;
  uint32_t  now;

  if (seat >= SEAT_COUNT)
  {
    return;
  }

  /* 把融合状态翻译成视觉状态（默认按空闲处理，宁可绿灯也别黑灯） */
  switch (seat_state)
  {
    case SEAT_OCCUPIED:    vis = LED_VIS_YELLOW;    break;
    case SEAT_FAKE_OCCUPY: vis = LED_VIS_RED_BLINK; break;
    case SEAT_IDLE:
    default:               vis = LED_VIS_GREEN;     break;
  }

  if (vis == s_vis[seat])
  {
    return;                 /* 状态没变：别重置计时，否则红灯永远等不到翻转 */
  }

  s_vis[seat] = vis;
  now = HAL_GetTick();

  if (vis == LED_VIS_RED_BLINK)
  {
    /* 一进告警先亮起来（上升沿立刻可见），之后每 LED_BLINK_MS 翻一次 */
    s_red_on[seat]     = true;
    s_blink_next[seat] = now + LED_BLINK_MS;
  }

  if (!s_flash_active)
  {
    led_seat_apply(seat);
  }
}

void LED_Task(void)
{
  uint32_t now;
  uint8_t  seat;

  /* 标定反馈窗口：期间保持 6 灯全亮，时间到就恢复各自状态 */
  if (s_flash_active)
  {
    if ((int32_t)(HAL_GetTick() - s_flash_until) < 0)
    {
      return;
    }
    s_flash_active = false;
    for (seat = 0U; seat < SEAT_COUNT; seat++)
    {
      led_seat_apply(seat);
    }
  }

  now = HAL_GetTick();

  for (seat = 0U; seat < SEAT_COUNT; seat++)
  {
    if (s_vis[seat] != LED_VIS_RED_BLINK)
    {
      continue;
    }
    /* 有符号比较：HAL_GetTick() 回绕（49 天）时也不会卡死 */
    if ((int32_t)(now - s_blink_next[seat]) < 0)
    {
      continue;
    }

    s_red_on[seat]     = (bool)!s_red_on[seat];
    s_blink_next[seat] = now + LED_BLINK_MS;
    led_seat_apply(seat);
  }
}

void LED_CalFlash(void)
{
  uint8_t seat;
  uint8_t i;

  for (seat = 0U; seat < SEAT_COUNT; seat++)
  {
    for (i = 0U; i < 3U; i++)
    {
      led_write(&s_led[seat][i], true);
    }
  }

  s_flash_active = true;
  s_flash_until  = HAL_GetTick() + LED_CAL_FLASH_MS;
}

/* --------------------- 灯序自检（装配 / 排查用） --------------------- */

#if LED_SELFTEST_ON_BOOT

#include <stdio.h>
#include "oled_ssd1306.h"    /* 自检时把"这一只是哪个脚"打在屏上 */

/** 自检提示表：座位 / 颜色 → LED 编号 + 引脚（与 s_led 表、led.h 文档一致） */
static const char *const s_led_name[SEAT_COUNT][3] =
{
  { "S1 LED2 PA5  GREEN", "S1 LED3 PA4  YELL", "S1 LED1 PB0  RED" },
  { "S2 LED5 PA9  GREEN", "S2 LED6 PA10 YELL", "S2 LED4 PA8  RED" }
};

void LED_SelfTest(void)
{
  uint8_t  seat;
  uint8_t  color;
  uint8_t  i;
  uint8_t  step = 0U;
  char     l[22];

  for (seat = 0U; seat < SEAT_COUNT; seat++)
  {
    for (color = 0U; color < 3U; color++)
    {
      /* 先全灭、再只点亮一只 —— 只有这样"该亮的没亮 / 不该亮的也亮"才看得出来 */
      for (i = 0U; i < SEAT_COUNT; i++)
      {
        led_seat_off(i);
      }
      led_write(&s_led[seat][color], true);

      step++;
      (void)snprintf(l, sizeof(l), "LED TEST %u/6", (unsigned)step);

      OLED_Clear();
      OLED_DrawString(0, 0, l);
      OLED_DrawString(0, 2, s_led_name[seat][color]);
      OLED_DrawString(0, 4, "ONLY THIS ONE ON");
      OLED_DrawString(0, 7, "POWER OFF TO EXIT");
      OLED_Refresh();

      HAL_Delay(900);      /* 阻塞没关系：这是上电诊断，跑完才进主循环 */
    }
  }

  for (i = 0U; i < SEAT_COUNT; i++)
  {
    led_seat_off(i);
  }
  OLED_Clear();
  OLED_DrawString(0, 2, "LED TEST DONE");
  OLED_Refresh();
  HAL_Delay(800);
}

#else

void LED_SelfTest(void)
{
  /* LED_SELFTEST_ON_BOOT = 0：编译成空操作，不占空间、不阻塞启动 */
}

#endif  /* LED_SELFTEST_ON_BOOT */
