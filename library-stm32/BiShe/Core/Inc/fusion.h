/**
  ******************************************************************************
  * @file    fusion.h
  * @brief   双感应融合判决（防假占座）：压力 + 红外状态机，**每座位一份**
  *
  * @note    V17 双座位：原来是单例（g_state / g_alarm / g_fake_start…），
  *          现在改成 `SeatFusion g_f[SEAT_COUNT]`，两个座位的计时、告警、PIR 可疑
  *          判定**完全独立** —— 座位 2 告警不会影响座位 1 的状态。
  ******************************************************************************
  */

#ifndef __FUSION_H
#define __FUSION_H

#include <stdint.h>
#include <stdbool.h>

#include "app_config.h"     /* SEAT_COUNT */

typedef enum {
  SEAT_IDLE = 0,          /* 空闲 */
  SEAT_OCCUPIED = 1,      /* 使用中（正常有人） */
  SEAT_FAKE_OCCUPY = 2    /* 假占座告警（有压力无红外持续 FAKE_OCCUPY_MS） */
} SeatState;

/**
 * @brief 一个座位的全部融合状态
 * @note  故意做成**全局**（不是 static），方便在 CubeIDE 的
 *        Live Expressions 里直接展开 g_f[0] / g_f[1] 看每个座位的计时。
 */
typedef struct
{
  SeatState state;          /* 当前融合状态 */
  bool      alarm;          /* 假占座告警标志（上升沿触发 fake_occupy 事件） */
  uint32_t  fake_start;     /* "有压力无红外"的计时起点（0 = 未计时） */
  uint32_t  hold_start;     /* 过渡态（压力没了、红外还在）的计时起点 */
  uint32_t  stuck_start;    /* "没压力却一直高"的计时起点（PIR 卡死检测） */
  uint8_t   pir_high_cnt;   /* 红外去抖计数 */
  bool      pir_suspect;    /* PIR 是否可疑（接反/型号不对/坏了） */
} SeatFusion;

extern SeatFusion g_f[SEAT_COUNT];

void FUSION_Init(void);

/**
 * @brief 推进一个座位的融合判决
 * @param seat     座位下标 0/1
 * @param pressed  该座位压力是否过阈值（SENSORS_IsPressedSeat）
 * @param pir      该座位红外原始电平（SENSORS_ReadPIRSeat）
 * @note  判定逻辑（每个座位独立跑同一套）：
 *          压力有 & 红外有 → 使用中
 *          压力有 & 红外无 → 持续 FAKE_OCCUPY_MS 判定假占座
 *          压力无 & 红外有 → 过渡态：给 PIR_HOLD_MS 窗口，超时回空闲
 *          压力无 & 红外无 → 空闲
 */
void FUSION_Update(uint8_t seat, bool pressed, bool pir);

SeatState FUSION_GetState(uint8_t seat);
bool      FUSION_GetAlarm(uint8_t seat);

/**
 * @brief PIR 是否"可疑"（大概率为接反 / 型号不对 / 坏了 / 前方有持续热源）
 * @note  判据：连续 PIR_STUCK_MS 都是"没有压力、红外却一直高"。
 *        红外回到低电平会自动解除。OLED 状态页显示 `PIR1!`。
 */
bool      FUSION_IsPirSuspect(uint8_t seat);

/** 强制把一个座位复位成空闲（清告警计时；云端 force_release 兼容分支用） */
void      FUSION_Reset(uint8_t seat);

#endif /* __FUSION_H */
