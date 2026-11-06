/* USER CODE BEGIN Header */
/**
  ******************************************************************************
  * @file           : main.h
  * @brief          : Header for main.c file.
  *                   This file contains the common defines of the application.
  ******************************************************************************
  * @attention
  *
  * Copyright (c) 2026 STMicroelectronics.
  * All rights reserved.
  *
  * This software is licensed under terms that can be found in the LICENSE file
  * in the root directory of this software component.
  * If no LICENSE file comes with this software, it is provided AS-IS.
  *
  ******************************************************************************
  */
/* USER CODE END Header */

/* Define to prevent recursive inclusion -------------------------------------*/
#ifndef __MAIN_H
#define __MAIN_H

#ifdef __cplusplus
extern "C" {
#endif

/* Includes ------------------------------------------------------------------*/
#include "stm32f1xx_hal.h"

/* Private includes ----------------------------------------------------------*/
/* USER CODE BEGIN Includes */

/* USER CODE END Includes */

/* Exported types ------------------------------------------------------------*/
/* USER CODE BEGIN ET */

/* USER CODE END ET */

/* Exported constants --------------------------------------------------------*/
/* USER CODE BEGIN EC */

/* USER CODE END EC */

/* Exported macro ------------------------------------------------------------*/
/* USER CODE BEGIN EM */

/* USER CODE END EM */

/* Exported functions prototypes ---------------------------------------------*/
void Error_Handler(void);

/* USER CODE BEGIN EFP */

/* USER CODE END EFP */

/* Private defines -----------------------------------------------------------*/
#define FSR1_ADC_Pin GPIO_PIN_0
#define FSR1_ADC_GPIO_Port GPIOA
#define BUT1_IN_Pin GPIO_PIN_2
#define BUT1_IN_GPIO_Port GPIOA
#define BUT2_IN_Pin GPIO_PIN_3
#define BUT2_IN_GPIO_Port GPIOA
#define HC_SR505_IN_Pin GPIO_PIN_7
#define HC_SR505_IN_GPIO_Port GPIOA
#define RFID_RST_Pin GPIO_PIN_1
#define RFID_RST_GPIO_Port GPIOB
#define ESP_TX_Pin GPIO_PIN_10
#define ESP_TX_GPIO_Port GPIOB
#define ESP_RX_Pin GPIO_PIN_11
#define ESP_RX_GPIO_Port GPIOB
#define RFID_SDA_Pin GPIO_PIN_12
#define RFID_SDA_GPIO_Port GPIOB
#define RFID_SCK_Pin GPIO_PIN_13
#define RFID_SCK_GPIO_Port GPIOB
#define RFID_MISO_Pin GPIO_PIN_14
#define RFID_MISO_GPIO_Port GPIOB
#define RFID_MOSI_Pin GPIO_PIN_15
#define RFID_MOSI_GPIO_Port GPIOB
#define BUZZ_I_O_Pin GPIO_PIN_5
#define BUZZ_I_O_GPIO_Port GPIOB
#define OLED_SCL_Pin GPIO_PIN_6
#define OLED_SCL_GPIO_Port GPIOB
#define OLED_SDA_Pin GPIO_PIN_7
#define OLED_SDA_GPIO_Port GPIOB
#define RFID_RQ_Pin GPIO_PIN_9
#define RFID_RQ_GPIO_Port GPIOB
/* ===== 双座位改造新增引脚（docs/05-双座位改造方案.md §1，已对照 2026-10-20 原理图核对） ===== */
#define HC_SR505_2_IN_Pin GPIO_PIN_6
#define HC_SR505_2_IN_GPIO_Port GPIOA
#define LED1_Pin GPIO_PIN_0
#define LED1_GPIO_Port GPIOB
#define LED2_Pin GPIO_PIN_5
#define LED2_GPIO_Port GPIOA
#define LED3_Pin GPIO_PIN_4
#define LED3_GPIO_Port GPIOA
#define LED4_Pin GPIO_PIN_8
#define LED4_GPIO_Port GPIOA
#define LED5_Pin GPIO_PIN_9
#define LED5_GPIO_Port GPIOA
#define LED6_Pin GPIO_PIN_10
#define LED6_GPIO_Port GPIOA
/* USER CODE BEGIN Private defines */
/* 第二路 FSR（原理图 FSR2）。CubeMX 里 PA1 目前**没有**分给 ADC2_IN1，
   这两行是手写的兜底定义；.ioc 已同步改成 ADC2_IN1，重新生成后本节仍保留。 */
#define FSR2_ADC_Pin GPIO_PIN_1
#define FSR2_ADC_GPIO_Port GPIOA
/* USER CODE END Private defines */

#ifdef __cplusplus
}
#endif

#endif /* __MAIN_H */
