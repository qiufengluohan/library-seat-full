/**
  ******************************************************************************
  * @file    esp_selftest.h
  * @brief   ESP8266 ↔ STM32 链路自检（联调期用）
  *
  * @note    测试分两层，**必须先分清是谁的问题**：
  *
  *          ① 回环自测（ESP_TestLoopback）
  *             把 ESP 模块**拔掉**，用一根杜邦线把 **PB10 与 PB11 短接**
  *             （即 STM32 的 TX 接回自己的 RX），然后发一串固定字节。
  *             能原样收回 ⇒ STM32 侧 USART3 + RX 中断 + 环形缓冲 全部正常。
  *             ⇒ 这一层通过后，再出问题就**一定在 ESP 模块/接线/供电**上。
  *
  *          ② AT 探测（ESP_TestAutoBaud）
  *             接好 ESP 模块，依次用 115200 / 9600 发 "AT"，等 "OK"。
  *             返回能通的波特率；两档都不通 ⇒ 模块没工作 / 接线反 / 供电不足。
  *
  *          开关在 app_config.h：
  *            ESP_SELFTEST_ON_BOOT   上电自动跑一次（测完可关）
  *            ESP_SELFTEST_LOOPBACK  1 = 跑①回环；0 = 跑②AT 探测
  ******************************************************************************
  */

#ifndef __ESP_SELFTEST_H
#define __ESP_SELFTEST_H

#include <stdint.h>
#include <stdbool.h>

/** 自检结论（也写进全局变量，便于 OLED 显示与 Live Expressions 观察） */
typedef enum {
  ESP_TEST_NOT_RUN = 0,      /* 还没测 */
  ESP_TEST_OK,               /* 通 */
  ESP_TEST_FAIL_NO_ECHO,     /* 回环都不通 ⇒ STM32 侧 UART/中断/短接线有问题 */
  ESP_TEST_FAIL_NO_AT        /* AT 无应答 ⇒ 模块、接线、供电或波特率的问题 */
} esp_test_result_t;

extern volatile esp_test_result_t g_esp_test_result;
extern volatile uint32_t g_esp_test_baud;   /* 探测成功的波特率；0 = 未确定 */
extern volatile uint16_t g_esp_test_rx_n;   /* 回环测试里实际收到的字节数（排错用） */

/** 回环自测：需拔掉 ESP，并把 PB10 与 PB11 短接。返回是否收到与发送完全一致的数据 */
bool     ESP_TestLoopback(uint32_t baud);

/** 依次用 115200 / 9600 发 "AT" 探测，返回可用的波特率；0 = 都不通 */
uint32_t ESP_TestAutoBaud(void);

/** 综合自检：按 app_config.h 的开关跑对应那一层，并把结果写到 OLED */
void     ESP_SelfTest(void);

#endif /* __ESP_SELFTEST_H */
