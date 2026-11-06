/**
  ******************************************************************************
  * @file    ui.h
  * @brief   OLED 统一显示层：**两座位状态面板** + **事件日志** + **标定页**
  *
  * @note    🔴 V17 双座位（docs/05-双座位改造方案.md §4）：三个页面 + 两个按键的
  *          短按/长按一共六种动作：
  *
  *            K1 短按（< 600 ms）  翻页：状态页 → 日志页 → 标定页 → 状态页
  *            K1 长按（≥ 1 s）     切换「当前座位」S1 ⇄ S2（标题 [S1] 闪两次）
  *            K2 短按（< 600 ms）  标定**当前座位**空载零点（OLED 记 CAL S1 OK，6 灯亮 0.3 s）
  *            K2 长按（≥ 2 s）     进入「全部标定」待确认（先提示 CLEAR BOTH SEATS，
  *                                 3 s 内再短按一次才执行）
  *
  *          ⚠️ 600~1000 ms 之间松开是**死区**（既不翻页也不切座位），这是方案里定死的口径。
  *          ⚠️ K1 长按是"按住到 1 s 立刻生效"，不是等松开；生效后松开不再翻页。
  *
  * @note    页面上「当前座位」的意义：标定页只显示/标定它，避免"屏幕显示 S1、
  *          按键却在标 S2"的误操作。状态页两个座位永远同时显示，不受当前座位影响。
  *
  * @note    刷新策略：日志/状态只写内存，**由 UI_Task() 节流统一刷屏**
  *          （整屏刷新在 I2C 100kHz 下要阻塞约 90ms，不能每次事件都刷）。
  *          切座位后的 [S1] 闪烁期间临时把节流降到 250 ms，否则闪不出两次。
  *
  * @note    ⚠️ UI_Log() 会做字符串格式化，**不要在中断里调用**（只在主循环/任务里用）。
  ******************************************************************************
  */

#ifndef __UI_H
#define __UI_H

#include <stdint.h>
#include <stdbool.h>

/** 显示模式（K1 短按循环切换：STATUS → LOG → FSR → STATUS） */
typedef enum {
  UI_MODE_STATUS = 0,     /* 状态面板：两个座位各一行 + 各模块健康度 */
  UI_MODE_LOG    = 1,     /* 事件日志 */
  UI_MODE_FSR    = 2      /* 标定页：只跟「当前座位」 */
} ui_mode_t;

/** 初始化（清空日志、复位模式、当前座位回 S1） */
void UI_Init(void);

/** 主循环调用：按键状态机（短按/长按）+ 节流刷新 */
void UI_Task(void);

/** 记一条事件（自动置脏，UI_Task 里统一刷屏） */
void UI_Log(const char *fmt, ...);

/**
 * @brief 更新某个座位的实时值（由 seat_app 每次采集后调用）
 * @param seat        座位下标 0/1（不是云端座位号）
 * @param pressure    该座位压力（0~4095）
 * @param pir         该座位红外电平 0/1
 * @param seat_state  fusion.h 的 SeatState：0 空闲 / 1 使用中 / 2 假占座
 */
void UI_SetSeatStatus(uint8_t seat, uint16_t pressure, uint8_t pir, uint8_t seat_state);

/** 更新"最近刷到的卡"（设备级，不分座位） */
void UI_SetUID(const char *uid);

/** 当前座位（0/1）—— 标定页与 K2 短按作用的对象 */
uint8_t UI_GetCurSeat(void);

/**
 * @brief OLED 自检图案：全亮 → 全灭 → 顶部/中部/底部分别画一行字
 *
 * @note  用来一眼分清两类问题：
 *          ① 若"全亮"时屏幕不是**整屏都亮**（只亮一条/一块）⇒
 *             寻址方式或控制器型号不匹配（典型：SH1106 模块用了 SSD1306 指令集）。
 *          ② 若文字上下颠倒 ⇒ COM 扫描方向（0xC8/0xC0）与该模块相反。
 *          ③ 若整屏正常亮灭、三行字位置也对 ⇒ 显示链路没问题，
 *             屏幕上出现的怪字符就**不是本固件画的**（固件没烧进去 / 烧到了旧程序）。
 */
void UI_ShowTestPattern(void);

void      UI_SetMode(ui_mode_t m);
ui_mode_t UI_GetMode(void);

/** 供 IDE 观察 */
extern volatile uint32_t g_ui_refresh_cnt;   /* 刷屏次数（可用来估算占用） */
extern volatile uint32_t g_ui_log_cnt;       /* 累计记录的事件条数 */

#endif /* __UI_H */
