/**
  ******************************************************************************
  * @file    esp8266.h
  * @brief   ESP8266-01S AT 指令驱动（UART 透传，适配通用 AT 1.x 固件）
  *
  * @note    本工程刻意不使用 AT+MQTT 系列指令（ESP8266-01S 多为 1MB Flash，
  *          原厂固件常不含 MQTT）。改为：AT 连 WiFi -> TCP 直连 OneNET MQTT
  *          Broker -> 进入透传模式 -> 由 mqtt_client 手写 MQTT 报文。
  *
  * @note    硬件：USART3，PB10 = USART3_TX -> 模块 RXD，PB11 = USART3_RX -> 模块 TXD
  *          （交叉接线）。接收走 RXNE 中断，需要在 USART3_IRQHandler 里调用
  *          ESP_UART_RX_ISR()，并在 usart.c 里使能 RXNE 中断。
  ******************************************************************************
  */

#ifndef __ESP8266_H
#define __ESP8266_H

#include <stdint.h>
#include <stdbool.h>

#define ESP_RX_BUF_LEN  512

/** 供 IDE 观察：累计收到的字节数与丢弃（缓冲满）次数 */
extern volatile uint32_t g_esp_rx_cnt;
extern volatile uint32_t g_esp_rx_drop;

/**
 * @brief 最近一次 AT+CWJAP（连热点）的结果码，**这是排"连不上 WiFi"最直接的线索**
 *          -1 = 还没收到结果（超时/还没连）
 *           1 = 连接超时    → 多半信号太弱，或 AP 其实是 5GHz
 *           2 = 密码错误    → 检查 WIFI_PASS
 *           3 = 找不到 AP   → 热点是 5GHz / 名称写错 / 距离太远
 *           4 = 连接失败    → 模块与 AP 不兼容（如 WPA3）、AP 已达上限
 */
extern volatile int g_esp_last_cwjap_err;

void ESP_Init(void);
void ESP_UART_RX_ISR(uint8_t b);          /* 由 USART3_IRQHandler 调用（RXNE 中断） */

bool ESP_SendAT(const char *cmd, const char *expect, uint32_t timeout_ms);
bool ESP_WaitFor(const char *kw, uint32_t timeout_ms);
void ESP_Drain(void);                      /* 丢弃当前接收缓冲 */

void ESP_SendRaw(const uint8_t *data, uint16_t len);   /* 透传模式发送原始字节 */

uint16_t ESP_Peek(uint8_t *out, uint16_t maxlen);      /* 拷贝当前可用字节（不消费） */
void ESP_Consume(uint16_t n);                          /* 消费 n 字节 */

bool ESP_ATReady(void);
void ESP_ExitTransparent(void);   /* 发送 "+++" 退出透传（断线恢复用） */
bool ESP_JoinWiFi(const char *ssid, const char *pwd);

/**
 * @brief 扫描附近热点，判断目标 SSID 是否能被模块"看见"
 * @note  排"连不上 WiFi"的关键一步：看不到 ⇒ 基本就是热点是 5GHz / 名称写错 / 距离太远；
 *        看得到却连不上 ⇒ 那就是密码、加密方式(WPA3)或超时的问题。
 *        注意本函数会阻塞约 5~10 秒（AT+CWLAP 本身较慢）。
 */
bool ESP_ScanForSSID(const char *ssid);
bool ESP_ConnectTCP(const char *host, uint16_t port);
bool ESP_EnterTransparent(void);

#endif /* __ESP8266_H */
