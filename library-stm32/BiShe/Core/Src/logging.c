/**
  ******************************************************************************
  * @file    logging.c
  * @brief   调试日志实现（阻塞式串口输出，仅用于开发期排错）
  ******************************************************************************
  */

#include "logging.h"
#include "main.h"
#include "usart.h"
#include "app_config.h"
#include <string.h>

void LOG_Init(void)
{
  /* USART3 由 MX_USART3_UART_Init 完成初始化，此处仅占位 */
}

void LOG_Str(const char *s)
{
#if LOG_ENABLED
  if (s == NULL) return;
  HAL_UART_Transmit(&huart3, (uint8_t *)s, (uint16_t)strlen(s), 100);
#else
  (void)s;
#endif
}

void LOG_Byte(uint8_t b)
{
#if LOG_ENABLED
  HAL_UART_Transmit(&huart3, &b, 1, 50);
#else
  (void)b;
#endif
}

void LOG_HexBuf(const uint8_t *buf, uint16_t len)
{
#if LOG_ENABLED
  static const char hex[] = "0123456789ABCDEF";
  for (uint16_t i = 0; i < len; i++)
  {
    uint8_t hi = hex[(buf[i] >> 4) & 0x0F];
    uint8_t lo = hex[buf[i] & 0x0F];
    LOG_Byte(hi); LOG_Byte(lo); LOG_Byte(' ');
  }
  LOG_Str("\r\n");
#else
  (void)buf; (void)len;
#endif
}
