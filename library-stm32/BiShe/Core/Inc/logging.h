/**
  ******************************************************************************
  * @file    logging.h
  * @brief   轻量调试日志
  *
  * @note    ⚠️ 本工程把 USART3 接给了 ESP8266，**没有空闲串口可以打日志**
  *          （往 USART3 打日志会和 AT 指令、MQTT 报文互相污染）。
  *          所以 LOG_ENABLED 默认置 0，日志代码在编译期被完全去掉，零开销。
  *
  *          调试建议改用 CubeIDE 的 **Live Expressions** 观察变量：
  *          g_fsr_raw / g_fsr_filtered / g_fsr_min / g_fsr_max / g_esp_rx_cnt 等。
  *
  *          确实需要串口日志时（例如单独测 WiFi 连接、ESP 侧不接模块）：
  *          把 LOG_ENABLED 改成 1 —— 此时日志会走 USART3，注意别和 ESP 同时用。
  ******************************************************************************
  */

#ifndef __LOGGING_H
#define __LOGGING_H

#include <stdint.h>
#include <stdbool.h>

#define LOG_ENABLED  0   /* 置 1 可打开日志（会占用 USART3，见文件头说明） */

void LOG_Init(void);
void LOG_Str(const char *s);
void LOG_Byte(uint8_t b);
void LOG_HexBuf(const uint8_t *buf, uint16_t len);

#if LOG_ENABLED
  #define LOG(...)     LOG_Str(__VA_ARGS__)
#else
  #define LOG(...)     ((void)0)
#endif

#endif /* __LOGGING_H */
