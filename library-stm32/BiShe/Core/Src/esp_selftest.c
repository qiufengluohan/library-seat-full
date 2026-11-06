/**
  ******************************************************************************
  * @file    esp_selftest.c
  * @brief   ESP8266 ↔ STM32 链路自检实现
  ******************************************************************************
  */

#include "esp_selftest.h"
#include "esp8266.h"
#include "app_config.h"
#include "oled_ssd1306.h"
#include "usart.h"
#include "main.h"

#include <string.h>
#include <stdio.h>

volatile esp_test_result_t g_esp_test_result = ESP_TEST_NOT_RUN;
volatile uint32_t g_esp_test_baud = 0;
volatile uint16_t g_esp_test_rx_n = 0;

/* 探测顺序：先试常见的 115200，再试老的 9600 */
static const uint32_t s_baud_list[] = { 115200UL, 9600UL };

/**
  * @brief 改 USART3 波特率并重新初始化
  * @note  ⚠️ HAL_UART_Init() 会重写 CR1 寄存器，**RXNE 中断必须重新打开**，
  *        否则改完波特率就再也收不到数据了（这个坑很隐蔽）。
  */
static bool esp_set_baud(uint32_t baud)
{
  if (huart3.Init.BaudRate == baud)
  {
    return true;
  }

  huart3.Init.BaudRate = baud;
  if (HAL_UART_Init(&huart3) != HAL_OK)
  {
    return false;
  }
  __HAL_UART_ENABLE_IT(&huart3, UART_IT_RXNE);   /* 重新使能 RXNE 中断 */
  return true;
}

/**
  * @brief 把一行字写到 OLED（第 0 行显示设备名，第 1 行显示测试结果）
  */
static void oled_show_2lines(const char *line1)
{
  OLED_Clear();
  OLED_DrawString(0, 0, DEVICE_NAME);
  OLED_DrawString(0, 1, line1);
  OLED_Refresh();
}

bool ESP_TestLoopback(uint32_t baud)
{
  static const char pattern[] = "STM32-ECHO-12345\r\n";
  const uint16_t plen = (uint16_t)(sizeof(pattern) - 1U);
  uint8_t  rx[64];
  uint16_t n = 0;
  uint16_t i;
  uint32_t t0;

  if (!esp_set_baud(baud))
  {
    return false;
  }

  ESP_Drain();
  ESP_SendRaw((const uint8_t *)pattern, plen);

  /* 等回环数据回来（最多 400ms；PB10/PB11 短接后是微秒级就能收到） */
  t0 = HAL_GetTick();
  while ((HAL_GetTick() - t0) < 400U)
  {
    n = ESP_Peek(rx, sizeof(rx));
    if (n >= plen)
    {
      break;
    }
    HAL_Delay(10);
  }
  n = ESP_Peek(rx, sizeof(rx));
  g_esp_test_rx_n = n;

  if (n != plen)
  {
    return false;                       /* 数量不对：TX 没发出去，或 RX 中断没工作 */
  }
  for (i = 0; i < plen; i++)
  {
    if (rx[i] != (uint8_t)pattern[i])
    {
      return false;                     /* 内容不对：波特率/时钟不对 */
    }
  }

  ESP_Consume(n);
  return true;
}

uint32_t ESP_TestAutoBaud(void)
{
  uint8_t k;

  for (k = 0U; k < (uint8_t)(sizeof(s_baud_list) / sizeof(s_baud_list[0])); k++)
  {
    uint32_t b = s_baud_list[k];
    if (!esp_set_baud(b))
    {
      continue;
    }

    ESP_Init();                          /* 每换一档都清一次接收缓冲 */
    HAL_Delay(50);

    /* 先关回显（有的模块回显开着，OK 之前会先回显 "AT"），再正式探测 */
    (void)ESP_SendAT("ATE0", "OK", 1000);
    ESP_Init();

    if (ESP_SendAT("AT", "OK", 2000))
    {
      return b;                          /* 这一档通了 */
    }
  }

  /* 两档都不通：把波特率恢复到默认 115200 ——
     否则会停在列表最后一档（9600），影响后续 ESP_ATReady()/连 WiFi 的尝试，
     出现"自检失败之后再也连不上"的假象。 */
  (void)esp_set_baud(115200UL);
  return 0U;                             /* 都不通 */
}

void ESP_SelfTest(void)
{
  char line[24];

  g_esp_test_result = ESP_TEST_NOT_RUN;
  g_esp_test_baud   = 0U;
  g_esp_test_rx_n   = 0U;

#if ESP_SELFTEST_LOOPBACK
  /* ---------- ① 回环自测：需拔掉 ESP，把 PB10↔PB11 短接 ---------- */
  if (ESP_TestLoopback(115200UL))
  {
    g_esp_test_result = ESP_TEST_OK;
    (void)snprintf(line, sizeof(line), "LOOP OK");
  }
  else
  {
    g_esp_test_result = ESP_TEST_FAIL_NO_ECHO;
    (void)snprintf(line, sizeof(line), "LOOP FAIL RX%u", (unsigned)g_esp_test_rx_n);
  }
  oled_show_2lines(line);
#else
  /* ---------- ② AT 探测：需接好 ESP 模块 ---------- */
  {
    uint32_t b = ESP_TestAutoBaud();
    g_esp_test_baud = b;

    if (b != 0U)
    {
      g_esp_test_result = ESP_TEST_OK;
      (void)snprintf(line, sizeof(line), "ESP OK %lu", (unsigned long)b);
    }
    else
    {
      g_esp_test_result = ESP_TEST_FAIL_NO_AT;
      (void)snprintf(line, sizeof(line), "ESP NO AT");
    }
    oled_show_2lines(line);
  }
#endif
}
