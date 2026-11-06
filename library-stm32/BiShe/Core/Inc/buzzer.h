/**
  ******************************************************************************
  * @file    buzzer.h
  * @brief   有源蜂鸣器驱动（高电平触发，非阻塞定时鸣叫）
  * @note    引脚：PB5（CubeMX 宏 BUZZ_I_O_Pin / BUZZ_I_O_GPIO_Port）
  ******************************************************************************
  */

#ifndef __BUZZER_H
#define __BUZZER_H

#include <stdint.h>

void BUZZER_Init(void);
void BUZZER_On(void);
void BUZZER_Off(void);
void BUZZER_Beep(uint32_t ms);   /* 启动一次定时鸣叫（非阻塞） */
void BUZZER_Task(void);          /* 主循环调用，到时自动停止 */

#endif /* __BUZZER_H */
