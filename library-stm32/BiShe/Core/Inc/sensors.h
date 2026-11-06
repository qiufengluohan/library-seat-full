/**
  ******************************************************************************
  * @file    sensors.h
  * @brief   按座位取数的传感器抽象：每个座位 1 路压力 FSR + 1 路 HC-SR505 红外
  *
  * @note    🔴 V17 双座位（docs/05-双座位改造方案.md §3）：
  *            座位 0（云端 seat_no = 1）→ FSR1 = PA0（ADC1_IN0）+ PIR1 = PA7
  *            座位 1（云端 seat_no = 2）→ FSR2 = PA1（ADC2_IN1）+ PIR2 = PA6
  *
  *          单座位时代"两片 FSR 是同一座位的两个压力点、上报取两者较大值"的
  *          理解**已作废**：双座位后一路 FSR 就是一个座位，**不能跨通道取最大值** ——
  *          否则一个座位坐人会把另一个也判成"有人"。
  *
  *          每路仍保留独立的零点 / 阈值 / 有效性判定（实现见 sensors.c）：
  *          没接或悬空的通道判为「该座位 FSR 不可信」，不参与"有人/无人"，
  *          也不会把垃圾值当成压力上报到云端。
  *
  *          阈值口径与单座位时代一致：**相对空载基线 + 增量**（上电或 K2 时学零点），
  *          云端下发的 adc_threshold 是**绝对阈值**，一改就覆盖两个座位。
  ******************************************************************************
  */

#ifndef __SENSORS_H
#define __SENSORS_H

#include <stdint.h>
#include <stdbool.h>

/** 上电初始化：ADC 自校准 + 采空载基线 + 算每座位阈值 */
void     SENSORS_Init(void);

/**
 * @brief 重新标定**两个座位**的空载零点并重算阈值（K2 长按 → 3 s 内确认）
 * @note  ⚠️ 调用前两个座位都必须没人，否则会把"有人"当成零点，之后再也判不出有人。
 */
void     SENSORS_Recalibrate(void);

/**
 * @brief 只重新标定**一个座位**的空载零点（K2 短按，标定页上的"当前座位"）
 * @note  另一个座位的零点、滤波窗口、极值统计完全不动。
 */
void     SENSORS_RecalibrateSeat(uint8_t seat);

/**
 * @brief 读该座位的压力（滑动平均后的 ADC 值，0~4095）
 * @note  **只用于显示与上报**；判断"是否有人"必须用 SENSORS_IsPressedSeat()。
 *        ⚠️ 该座位通道被判为无效（没接/悬空）时返回 0 —— 这样云端曲线是一条干净的
 *        0 线，而不是几百的乱漂值；真实原始值请到 OLED 标定页看 P/F。
 */
uint16_t SENSORS_ReadPressureSeat(uint8_t seat);

/** @brief 该座位是否受压（拿这一路自己的阈值比；通道无效 ⇒ 恒 false） */
bool     SENSORS_IsPressedSeat(uint8_t seat);

/** @brief 读该座位的红外 GPIO（true = 有人） */
bool     SENSORS_ReadPIRSeat(uint8_t seat);

/** @brief 云端下发的 adc_threshold（**绝对**阈值，覆盖两个座位，并信任它） */
void     SENSORS_SetThreshold(int th);

/** @brief 汇总阈值：两个有效座位里较小的那个（用于显示/日志；都无效时返回默认值） */
int      SENSORS_GetThreshold(void);

/** @brief 指定座位自己的阈值；返回 **-1 表示该座位 FSR 不可信**（没接/悬空） */
int      SENSORS_GetThresholdSeat(uint8_t seat);

/** @brief 指定座位的空载基线（标定页显示用，不做有效性过滤） */
uint16_t SENSORS_GetZeroSeat(uint8_t seat);

/** @brief 该座位的 FSR 通道是否可信（false ⇒ 没接/悬空，不参与判定） */
bool     SENSORS_IsSeatValid(uint8_t seat);

/**
 * @brief 座位 → FSR 通道号（0 = PA0/ADC1，1 = PA1/ADC2）
 * @note  给显示层画标定页用：一定要通过这个函数拿通道号，
 *        不要在 ui.c 里写 `ch = seat` —— 否则将来换通道映射时，
 *        标定页的 RAW/FLT/MIN/MAX 会跟 THR/Z 对不上，很难查。
 */
uint8_t  SENSORS_SeatChannel(uint8_t seat);

#endif /* __SENSORS_H */
