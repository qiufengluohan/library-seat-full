/**
  ******************************************************************************
  * @file    sensors.c
  * @brief   按座位取数的传感器实现（V17 双座位）
  *
  * 座位 ↔ 硬件（一一对应，不再跨通道取最大值）：
  *
  *   座位 0（seat_no=1）：FSR1 = PA0 / ADC1_IN0，PIR1 = PA7
  *   座位 1（seat_no=2）：FSR2 = PA1 / ADC2_IN1，PIR2 = PA6
  *
  * 每座位独立的零点 / 阈值 / 有效性：
  *
  *   零点   g_zero[seat] = 上电（或 K2）时采到的空载值
  *   阈值   g_thr[seat]  = 零点 + PRESSURE_DELTA_DEF（夹在 [MIN, 4095]）
  *   有效性              = 零点必须"像一个接了 10.1k 下拉的空座"（0 ~ PRESSURE_ZERO_MAX）
  *
  *   判定 = 该座位这一路超过**它自己的**阈值
  *
  * 为什么保留有效性检查：换座子 / 焊点接触不良 / 引脚悬空时，
  * 空载值会在几百~900 乱漂。这种通道既不能判"有人"（会误报），
  * 也不该把乱漂值当压力上报（云端曲线会变成噪声、上报节流会被它一直触发）。
  ******************************************************************************
  */

#include "sensors.h"
#include "fsr.h"
#include "app_config.h"
#include "main.h"

/* 每座位一份：零点 / 阈值 / 有效性（下标 = 座位下标，不是云端座位号） */
static uint16_t g_zero[SEAT_COUNT];
static int      g_thr[SEAT_COUNT];
static bool     g_seat_valid[SEAT_COUNT];

/* 显示/日志用的汇总阈值（取所有有效座位里最小的那一个） */
static int      g_threshold = (int)PRESSURE_THRESHOLD_DEF;

/* ---------------------------- 内部函数 ---------------------------- */

/** 座位 → FSR 通道。座位 0 = PA0，座位 1 = PA1（见 app_config.h 的 SEAT_COUNT 注释） */
static uint8_t sensors_seat_ch(uint8_t seat)
{
  return (seat == 0U) ? FSR_CH1 : FSR_CH2;
}

/** 用一个座位的当前零点重算它的阈值与有效性 */
static void sensors_apply_seat(uint8_t seat)
{
  uint8_t  ch    = sensors_seat_ch(seat);
  uint16_t z     = FSR_GetZero(ch);
  uint32_t delta = (uint32_t)PRESSURE_DELTA_DEF;

  if (delta < (uint32_t)PRESSURE_DELTA_MIN)
  {
    delta = (uint32_t)PRESSURE_DELTA_MIN;
  }

  g_zero[seat] = z;

  if (!FSR_CH_ACTIVE(ch))
  {
    /* 没被选通：不参与判定（V17 固定 0x03，正常不会走到这里） */
    g_seat_valid[seat] = false;
    g_thr[seat]        = (int)PRESSURE_THRESHOLD_DEF;
    return;
  }

  if (z <= PRESSURE_ZERO_MAX)
  {
    /* 正常：有下拉 + 空座 ⇒ 相对阈值（软坐垫、坐垫预压也能判出人） */
    uint32_t th = (uint32_t)z + delta;
    if (th > 4095U)
    {
      th = 4095U;
    }
    g_thr[seat]        = (int)th;
    g_seat_valid[seat] = true;
  }
  else if (z >= PRESSURE_ZERO_OCCUPIED)
  {
    /* 上电时座位上就有人 / 坐垫预压很大 ⇒ 保留通道，改用绝对阈值 */
    g_thr[seat]        = (int)PRESSURE_THRESHOLD_DEF;
    g_seat_valid[seat] = true;
  }
  else
  {
    /* ZERO_MAX ~ ZERO_OCCUPIED 之间：半悬空 / 没接好 ⇒ 退出判定。
       典型就是"引脚浮到 400~900"，绝不能让它参与"有人/无人"。 */
    g_thr[seat]        = (int)PRESSURE_THRESHOLD_DEF;
    g_seat_valid[seat] = false;
  }
}

/** 两个座位都算完之后刷新汇总阈值 */
static void sensors_refresh_summary(void)
{
  uint8_t seat;
  int     thr_min = 4095;
  bool    any     = false;

  for (seat = 0U; seat < SEAT_COUNT; seat++)
  {
    if (!g_seat_valid[seat])
    {
      continue;
    }
    any = true;
    if (g_thr[seat] < thr_min)
    {
      thr_min = g_thr[seat];
    }
  }

  /* 两个座位都不可信 ⇒ 退回绝对阈值，别把设备判死 */
  g_threshold = any ? thr_min : (int)PRESSURE_THRESHOLD_DEF;
}

/* ------------------------------ 接口 ------------------------------ */

void SENSORS_Init(void)
{
  uint8_t seat;

  FSR_Init();          /* 含两路 ADC 自校准、滤波器初始化、空载基线采样 */

  for (seat = 0U; seat < SEAT_COUNT; seat++)
  {
    sensors_apply_seat(seat);
  }
  sensors_refresh_summary();
}

void SENSORS_Recalibrate(void)
{
  uint8_t seat;

  /* ⚠️ 调用前两个座位都必须没人 */
  FSR_Rezero();

  for (seat = 0U; seat < SEAT_COUNT; seat++)
  {
    sensors_apply_seat(seat);
  }
  sensors_refresh_summary();
}

void SENSORS_RecalibrateSeat(uint8_t seat)
{
  if (seat >= SEAT_COUNT)
  {
    return;
  }

  /* ⚠️ 调用前这个座位必须没人；另一路的零点/滤波窗口完全不动 */
  FSR_RezeroCh(sensors_seat_ch(seat));
  sensors_apply_seat(seat);
  sensors_refresh_summary();
}

uint16_t SENSORS_ReadPressureSeat(uint8_t seat)
{
  uint8_t  ch;
  uint16_t p;

  if (seat >= SEAT_COUNT)
  {
    return 0U;
  }

  ch = sensors_seat_ch(seat);

  /* 始终采样 + 滤波：即使在标定页上也要能看到 F 值实时变化。
     ⚠️ 返回值只用于**显示与上报**；"是否有人"请用 SENSORS_IsPressedSeat()。 */
  p = FSR_ReadFiltered(ch);

  /* 通道不可信（没接/悬空）⇒ 上报 0，不要拿几百的乱漂值去污染云端曲线 */
  return g_seat_valid[seat] ? p : 0U;
}

bool SENSORS_IsPressedSeat(uint8_t seat)
{
  if ((seat >= SEAT_COUNT) || !g_seat_valid[seat])
  {
    return false;      /* 该路不可信 ⇒ 永远判"无人"，绝不误报 */
  }

  return (FSR_GetLast(sensors_seat_ch(seat)) > (uint16_t)g_thr[seat]);
}

bool SENSORS_ReadPIRSeat(uint8_t seat)
{
  /* HC-SR505：有人输出高电平；两个引脚都在 CubeMX 里配为输入 + 下拉 */
  if (seat == 1U)
  {
    return (HAL_GPIO_ReadPin(HC_SR505_2_IN_GPIO_Port, HC_SR505_2_IN_Pin) == GPIO_PIN_SET);
  }
  return (HAL_GPIO_ReadPin(HC_SR505_IN_GPIO_Port, HC_SR505_IN_Pin) == GPIO_PIN_SET);
}

void SENSORS_SetThreshold(int th)
{
  uint8_t seat;

  if (th < 0 || th > 4095)
  {
    return;
  }

  /* 云端下发的是**绝对** ADC 阈值：覆盖两个座位，并信任它（不再看有效性） */
  for (seat = 0U; seat < SEAT_COUNT; seat++)
  {
    g_thr[seat]        = th;
    g_seat_valid[seat] = true;
  }
  g_threshold = th;
}

int SENSORS_GetThreshold(void)
{
  return g_threshold;
}

int SENSORS_GetThresholdSeat(uint8_t seat)
{
  if ((seat >= SEAT_COUNT) || !g_seat_valid[seat])
  {
    return -1;          /* -1 = 该座位 FSR 不可信（没接 / 悬空） */
  }
  return g_thr[seat];
}

uint16_t SENSORS_GetZeroSeat(uint8_t seat)
{
  return (seat < SEAT_COUNT) ? g_zero[seat] : 0U;
}

bool SENSORS_IsSeatValid(uint8_t seat)
{
  return (seat < SEAT_COUNT) ? g_seat_valid[seat] : false;
}

uint8_t SENSORS_SeatChannel(uint8_t seat)
{
  return (seat < SEAT_COUNT) ? sensors_seat_ch(seat) : 0U;
}
