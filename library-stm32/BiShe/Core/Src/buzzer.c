/**
  ******************************************************************************
  * @file    buzzer.c
  * @brief   蜂鸣器实现（非阻塞）
  * @note    引脚用 CubeMX 生成的宏：BUZZ_I_O_Pin（PB5），复位后为低=不响。
  ******************************************************************************
  */

#include "buzzer.h"
#include "main.h"
#include "app_config.h"     /* BUZZER_ENABLED 开关 */

static uint32_t g_beep_until = 0;

void BUZZER_Init(void)
{
  HAL_GPIO_WritePin(BUZZ_I_O_GPIO_Port, BUZZ_I_O_Pin, GPIO_PIN_RESET);
  g_beep_until = 0;
}

#if BUZZER_ENABLED

void BUZZER_On(void)
{
  HAL_GPIO_WritePin(BUZZ_I_O_GPIO_Port, BUZZ_I_O_Pin, GPIO_PIN_SET);
}

void BUZZER_Off(void)
{
  HAL_GPIO_WritePin(BUZZ_I_O_GPIO_Port, BUZZ_I_O_Pin, GPIO_PIN_RESET);
  g_beep_until = 0;
}

void BUZZER_Beep(uint32_t ms)
{
  if (ms == 0) return;
  BUZZER_On();
  g_beep_until = HAL_GetTick() + ms;
}

void BUZZER_Task(void)
{
  if (g_beep_until != 0 && (HAL_GetTick() >= g_beep_until))
  {
    BUZZER_Off();
  }
}

#else   /* BUZZER_ENABLED == 0：没接蜂鸣器 —— 全部空操作，不点引脚、零开销 */

void BUZZER_On(void)  { }
void BUZZER_Off(void) { g_beep_until = 0; }
void BUZZER_Beep(uint32_t ms) { (void)ms; }
void BUZZER_Task(void) { }

#endif  /* BUZZER_ENABLED */
