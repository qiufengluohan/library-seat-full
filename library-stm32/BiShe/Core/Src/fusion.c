/**
  ******************************************************************************
  * @file    fusion.c
  * @brief   双感应融合判决实现（V17：每座位一份独立状态）
  *
  * @note    判定逻辑（对每个座位各跑一遍）：
  *   压力有 & 红外有 -> 使用中
  *   压力有 & 红外无 -> 持续 FAKE_OCCUPY_MS 判定假占座
  *   压力无 & 红外有 -> 过渡态：给 PIR_HOLD_MS 的窗口，**超时回空闲**
  *   压力无 & 红外无 -> 空闲
  *
  *   ⚠️ 过渡态不能用"永久保持当前状态"：HC-SR505 一旦卡在常高
  *      （接反 / 型号不对 / 前方有热源），座位会被永久锁在 USED，再也回不到 IDLE。
  *      所以用 PIR_HOLD_MS 做超时。
  *
  *   另加 PIR 卡死检测：连续 PIR_STUCK_MS 都是"没压力但红外一直高"
  *   ⇒ 认为 PIR 不可信，置 pir_suspect，OLED 状态页显示 `PIR1!`。
  *
  *   🔴 双座位：所有计时/标志都挂在 g_f[seat] 上，两个座位互不影响。
  ******************************************************************************
  */

#include "fusion.h"
#include "app_config.h"
#include "main.h"

/** 每个座位一份融合状态（全局，便于 IDE 观察；见 fusion.h 注释） */
SeatFusion g_f[SEAT_COUNT];

void FUSION_Reset(uint8_t seat)
{
  if (seat >= SEAT_COUNT)
  {
    return;
  }

  g_f[seat].state        = SEAT_IDLE;
  g_f[seat].alarm        = false;
  g_f[seat].fake_start   = 0U;
  g_f[seat].hold_start   = 0U;
  g_f[seat].stuck_start  = 0U;
  g_f[seat].pir_high_cnt = 0U;
  g_f[seat].pir_suspect  = false;
}

void FUSION_Init(void)
{
  uint8_t seat;

  for (seat = 0U; seat < SEAT_COUNT; seat++)
  {
    FUSION_Reset(seat);
  }
}

void FUSION_Update(uint8_t seat, bool pressed, bool pir)
{
  SeatFusion *f;
  bool        pir_ok;
  uint32_t    now;

  if (seat >= SEAT_COUNT)
  {
    return;
  }

  f   = &g_f[seat];
  now = HAL_GetTick();

  /* 红外去抖：连续 PIR_CHECK_COUNT 次高电平才认可
     （HC-SR505 输出有约 8 s 保持，但去抖仍能滤掉上电毛刺） */
  if (pir)
  {
    if (f->pir_high_cnt < PIR_CHECK_COUNT)
    {
      f->pir_high_cnt++;
    }
  }
  else
  {
    f->pir_high_cnt = 0U;
  }
  pir_ok = (f->pir_high_cnt >= PIR_CHECK_COUNT);

  /* ---- PIR 卡死检测 ----
     正常 HC-SR505 触发后最多保持约 8 s。若"没有压力、红外却持续高"超过 PIR_STUCK_MS，
     基本可以断定模块接反 / 型号不对（例如 AM312 用 5V 供电）/ 坏了 / 前方有持续热源。 */
  if (!pressed && pir_ok)
  {
    if (f->stuck_start == 0U)
    {
      f->stuck_start = now;
    }
    else if ((now - f->stuck_start) >= PIR_STUCK_MS)
    {
      f->pir_suspect = true;
    }
  }
  else
  {
    f->stuck_start = 0U;
    if (!pir)
    {
      f->pir_suspect = false;      /* 红外真的回到低电平 ⇒ 解除怀疑 */
    }
  }

  if (pressed && pir_ok)
  {
    f->state      = SEAT_OCCUPIED;
    f->fake_start = 0U;
    f->hold_start = 0U;
    f->alarm      = false;
  }
  else if (pressed && !pir_ok)
  {
    f->hold_start = 0U;
    if (f->fake_start == 0U)
    {
      f->fake_start = now;
    }
    else if ((now - f->fake_start) >= FAKE_OCCUPY_MS)
    {
      f->state = SEAT_FAKE_OCCUPY;
      f->alarm = true;
    }
  }
  else if (!pressed && pir_ok)
  {
    /* 过渡态：压力刚消失、红外还有残留（HC-SR505 触发后保持约 8 s）。
       窗口内保持原状态；超过 PIR_HOLD_MS 认为人已离开（或 PIR 卡死）⇒ 回空闲。 */
    if (f->hold_start == 0U)
    {
      f->hold_start = now;
    }
    else if ((now - f->hold_start) >= PIR_HOLD_MS)
    {
      f->state      = SEAT_IDLE;
      f->fake_start = 0U;
      f->alarm      = false;
      f->hold_start = 0U;
    }
  }
  else
  {
    f->state      = SEAT_IDLE;
    f->fake_start = 0U;
    f->alarm      = false;
    f->hold_start = 0U;
  }
}

SeatState FUSION_GetState(uint8_t seat)
{
  return (seat < SEAT_COUNT) ? g_f[seat].state : SEAT_IDLE;
}

bool FUSION_GetAlarm(uint8_t seat)
{
  return (seat < SEAT_COUNT) ? g_f[seat].alarm : false;
}

bool FUSION_IsPirSuspect(uint8_t seat)
{
  return (seat < SEAT_COUNT) ? g_f[seat].pir_suspect : false;
}
