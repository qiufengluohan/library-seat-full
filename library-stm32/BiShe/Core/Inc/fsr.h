/**
  ******************************************************************************
  * @file    fsr.h
  * @brief   FSR402 柔性薄膜压力传感器采集（双路，轮询方式）
  *          FSR1 -> PA0 -> ADC1_IN0
  *          FSR2 -> PA1 -> ADC2_IN1
  *
  * 说明：
  *   - 采用「软件触发 + 单次转换」的轮询方式，不用 DMA（慢变信号，够用且好调试）。
  *   - STM32F1 的 ADC 首次转换前必须自校准，本模块的 FSR_Init() 已包含。
  *   - 滤波用滑动平均（窗口见 FSR_FILTER_LEN），用于抑制传感器抖动。
  ******************************************************************************
  */

#ifndef __FSR_H__
#define __FSR_H__

#ifdef __cplusplus
extern "C" {
#endif

#include "main.h"

/* ---------------------------- 可调参数 ---------------------------- */

/** 滤波窗口长度（取 2 的幂，便于理解；越大越平滑但越迟钝） */
#define FSR_FILTER_LEN        8

/** 单次转换的轮询超时（ms）。ADC 一次转换约 6us，给 10ms 已非常宽松 */
#define FSR_POLL_TIMEOUT_MS   10

/** ADC 满量程（12 位）与参考电压，用于换算 mV */
#define FSR_ADC_FULLSCALE     4095U
#define FSR_VDDA_MV           3300U

/**
 * @brief 「有人坐下」的判定阈值不在这里定义
 * @note  统一放在 app_config.h 的 PRESSURE_THRESHOLD_DEF（因为它可被云端 adc_threshold 覆盖）。
 *        标定方法：上电后让 FSR 空置，看 g_fsr_filtered[] 的稳定值（记为 V0）；
 *        再用手/书本压住传感器，看稳定值（记为 V1）；
 *        阈值取 V0 + (V1 - V0) * 40%。
 *        判定用 FSR_IsPressed(ch, threshold)，阈值由调用方传入。
 */

/* ------------------------- 通道与调试观测变量 ------------------------- */

/** 通道索引：0 = FSR1(PA0/ADC1)，1 = FSR2(PA1/ADC2) */
#define FSR_CH1               0U
#define FSR_CH2               1U
#define FSR_CH_NUM            2U

/**
 * @brief 供 IDE 里用 Live Expressions / Watch 直接观察的全局变量
 * @note  这些是 volatile，便于在 CubeIDE 调试时实时看值，不需占用串口打印。
 *        （USART3 已经接给 ESP8266 了，不能用它打调试信息，否则会和 AT 指令打架）
 */
extern volatile uint16_t g_fsr_raw[FSR_CH_NUM];       /**< 最近一次原始值 */
extern volatile uint16_t g_fsr_filtered[FSR_CH_NUM];  /**< 最近一次滑动平均值 */
extern volatile uint16_t g_fsr_min[FSR_CH_NUM];       /**< 标定统计：最小值 */
extern volatile uint16_t g_fsr_max[FSR_CH_NUM];       /**< 标定统计：最大值 */
extern volatile uint32_t g_fsr_err_cnt[FSR_CH_NUM];   /**< 采样失败计数（正常应恒为 0） */
extern volatile uint16_t g_fsr_zero[FSR_CH_NUM];      /**< 上电空载基线（判定阈值的零点） */

/* ------------------------------- 接口 ------------------------------- */

/**
 * @brief 初始化：对两路 ADC 做自校准，并复位滤波器与标定统计
 * @note  必须在 MX_ADC1_Init() / MX_ADC2_Init() 之后调用
 */
void     FSR_Init(void);

/**
 * @brief 重新采集「空载基线」并复位滤波窗口
 * @note  用于：① 上电时座位上有人导致基线偏高；② 坐垫被压久了产生永久变形/预压。
 *        调用前请确保座位上**没人**。由 UI 的 K2 触发。
 */
void     FSR_Rezero(void);

/**
 * @brief 只重新采集**某一路**的空载基线（V17 双座位：一个座位一条通道）
 * @param ch FSR_CH1(PA0) / FSR_CH2(PA1)
 * @note  另一路的零点与滤波窗口完全不动 —— K2 短按只标当前座位就靠它。
 */
void     FSR_RezeroCh(uint8_t ch);

/** @brief 采一次指定通道的原始值（0..4095）；失败返回上次成功值 */
uint16_t FSR_ReadRaw(uint8_t ch);

/** @brief 采一次并做滑动平均，返回滤波后的值 */
uint16_t FSR_ReadFiltered(uint8_t ch);

/** @brief 取最近一次滤波值（不触发新采样） */
uint16_t FSR_GetLast(uint8_t ch);

/**
 * @brief 取「上电空载基线」（FSR_Init 末尾采到的滤波值）
 * @note  用来把固定阈值改成「基线 + 增量」，这样即使坐垫预压、传感器个体差异、
 *        温漂把空载值抬到 100~300，也不会一直误判"有人"或永远判不出"有人"。
 *        ⚠️ 上电那一刻座位上不要坐人，否则基线会被抬高。
 */
uint16_t FSR_GetZero(uint8_t ch);

/** @brief 把最近一次原始值换算成电压（mV） */
uint16_t FSR_GetMv(uint8_t ch);

/**
 * @brief 采样两路并更新滤波器 + 标定统计
 * @note  放在主循环里反复调用即可，内部开销约 12us，可忽略
 */
void     FSR_Poll(void);

/** @brief 清零标定统计（重新开始一轮标定前调用） */
void     FSR_DebugReset(void);

/** @brief 判定该通道压力是否超过阈值 */
uint8_t  FSR_IsPressed(uint8_t ch, uint16_t threshold);

/**
 * @brief 读芯片内部参考电压 VREFINT（ADC 通道 17）—— **用来判断 ADC 本身好不好**
 *
 * @note  正常值 ≈ 1.20 V / 3.3 V × 4095 ≈ **1480**（数据手册 1.16~1.24 V ⇒ 1430~1530）
 *          · 读到 1300~1700   ⇒ ADC 与 VDDA 都正常 ⇒ 问题在外部电路（FSR 接线）
 *          · 读到 ~0 或 ~4095 ⇒ ADC/VDDA 本身异常（或引脚损坏）
 *        读完会自动把通道恢复成 CHANNEL_0（PA0），不影响后续采样。
 */
uint16_t FSR_ReadVrefint(void);

/** @brief 开机时读到的 VREFINT 原始值（OLED 第 7 行显示，也便于 IDE 观察） */
extern volatile uint16_t g_fsr_vref;

#ifdef __cplusplus
}
#endif

#endif /* __FSR_H__ */
