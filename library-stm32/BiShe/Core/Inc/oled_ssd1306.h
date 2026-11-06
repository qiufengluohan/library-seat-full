/**
  ******************************************************************************
  * @file    oled_ssd1306.h
  * @brief   0.96 寸 OLED（SSD1306，I2C）驱动
  * @note    仅内置基础 ASCII（大写+数字+少量符号）5x7 字体，够显示状态用。
  *          需要显示中文请自行用字模工具生成字体并替换 font 表。
  *          硬件：I2C1（PB6=SCL / PB7=SDA），从机地址 0x3C（7 位）。
  ******************************************************************************
  */

#ifndef __OLED_SSD1306_H
#define __OLED_SSD1306_H

#include <stdint.h>
#include <stdbool.h>

/** 供 IDE 观察：I2C 写失败次数（OLED 没插好/接线松时会一直涨，正常应恒为 0） */
extern volatile uint32_t g_oled_i2c_err;

/** 运行期实际使用的 7 位 I2C 地址（初始化时自动探测 0x3C~0x3F） */
extern volatile uint8_t g_oled_i2c_addr;

/** OLED 是否在总线上应答过：0 = 没找到（接线错/没供电/地址不在常见范围） */
extern volatile uint8_t g_oled_found;

void OLED_Init(void);
void OLED_Clear(void);
/** 用同一个字节填满整个帧缓冲（0xFF=全亮，0x00=全灭）。自检/验证寻址时用 */
void OLED_Fill(uint8_t pattern);

/** 只把某一页（8 行像素，page 0..7）填成 pattern，其余页不动。逐行扫描自检用 */
void OLED_SetPagePattern(uint8_t page, uint8_t pattern);
void OLED_Refresh(void);                 /* 把帧缓冲刷到屏幕（阻塞，见 .c 里的说明） */
void OLED_DrawString(uint8_t x, uint8_t y, const char *s); /* y 以「行(8px)」为单位 */
void OLED_ShowStatus(uint8_t display_code, bool alarm);     /* 0空闲 1已约 2使用 3暂离 4告警 */

#endif /* __OLED_SSD1306_H */
