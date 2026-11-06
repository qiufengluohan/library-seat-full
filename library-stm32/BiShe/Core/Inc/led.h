/**
  ******************************************************************************
  * @file    led.h
  * @brief   每座位 3 只三色灯：绿=空闲 / 黄=使用中 / 红闪=告警（三态互斥）
  *
 * @note    引脚（按**实物装配**，2026-10-29 现场核对；原理图 2026-10-20）：
 *            座位 1：红 LED1 = PB0，绿 LED2 = PA5，黄 LED3 = PA4
 *            座位 2：红 LED4 = PA8，绿 LED5 = PA9，黄 LED6 = PA10
 *          接法：引脚 → 330Ω → LED → GND ⇒ **高电平点亮**。
 *
 * @note    ⚠️ 原理图只画了 LED1~LED6 六个灯位，**颜色是焊上去才确定的**。
 *          实物后来按"红1/绿2/黄3、红4/绿5/黄6"焊，与最初设想的"绿黄红"
 *          顺序不同，所以 `led.c` 的 s_led 表是按颜色映射的，不是按编号顺序。
 *          重焊/改板时：**先量实物，再改 s_led 表和本注释**，状态机不用动。
  *
  * @note    🔴 全部**非阻塞**：LED_Task() 每轮主循环推进（翻灯/计时），
  *          严禁在这里用 HAL_Delay() —— 主循环里还有按键扫描与 MQTT 收发。
  *
  * @note    ⚠️ 本模块只管"灯怎么亮"，不管"座位是什么状态"：
  *          座位状态由 fusion.c 判定，seat_app 把它转给 LED_SetSeatState()。
  ******************************************************************************
  */

#ifndef __LED_H
#define __LED_H

#include <stdint.h>
#include <stdbool.h>

/** 初始化：把 6 只灯全灭并复位内部状态（必须在 MX_GPIO_Init() 之后调用） */
void LED_Init(void);

/**
 * @brief 设置某个座位的灯态（三态互斥：绿 / 黄 / 红闪）
 * @param seat        座位下标 0/1（不是云端座位号 1/2）
 * @param seat_state  fusion.h 的 SeatState：SEAT_IDLE / SEAT_OCCUPIED / SEAT_FAKE_OCCUPY
 * @note  状态没变化时直接返回，不会打断正在进行的闪烁节奏。
 */
void LED_SetSeatState(uint8_t seat, uint8_t seat_state);

/** 主循环调用：推进告警红灯闪烁（250 ms 翻转 ⇒ 2 Hz）与标定反馈的恢复 */
void LED_Task(void);

/** 标定成功反馈：6 只灯全亮 LED_CAL_FLASH_MS（300 ms），到点自动恢复各自状态 */
void LED_CalFlash(void);

/**
 * @brief 灯序自检：逐个点亮 LED1~LED6，OLED 同步显示"这一只是哪个脚、什么颜色"
 * @note  由 app_config.h 的 LED_SELFTEST_ON_BOOT 控制是否在上电时调用。
 *        **阻塞**约 6 秒，只用于装配后确认焊接与灯序，跑完把开关改回 0。
 *
 *        判读方法：自检期间**只有当前这一只该亮**，其余 5 只要全灭。
 *          · 该亮却不亮        ⇒ LED 焊反/损坏、串联电阻开路、或没接到这个脚
 *          · 不该亮却一直亮    ⇒ 该脚被外部拉高（焊到 3.3V / 与别的网络短路）
 *          · 亮的位置不对      ⇒ 焊错位置（装配约定见文件头）
 */
void LED_SelfTest(void);

#endif /* __LED_H */
