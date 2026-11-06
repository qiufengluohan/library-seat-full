/**
  ******************************************************************************
  * @file    esp8266.c
  * @brief   ESP8266 AT 指令驱动实现（环形缓冲 + 透传）
  *
  * @note    串口从旧工程的 USART1 改为本工程的 **USART3**（PB10/PB11）。
  ******************************************************************************
  */

#include "esp8266.h"
#include "main.h"
#include "usart.h"
#include "app_config.h"
#include "logging.h"
#include "ui.h"          /* 把连接结果直接记进 OLED 事件日志 */

#include <string.h>
#include <stdio.h>
#include <stdlib.h>      /* atoi（解析 +CWJAP 错误码） */

/* 接收环形缓冲：USART3 RX 中断写入，主循环读取解析 */
static uint8_t rx_buf[ESP_RX_BUF_LEN];
static volatile uint16_t rx_head = 0;
static uint16_t rx_tail = 0;

/* 调试观察量 */
volatile uint32_t g_esp_rx_cnt = 0;
volatile uint32_t g_esp_rx_drop = 0;

/* 最近一次 AT+CWJAP 的结果码（-1=还没结果；1..4=模块报的错误码） */
volatile int g_esp_last_cwjap_err = -1;

void ESP_Init(void)
{
  rx_head = 0;
  rx_tail = 0;
  g_esp_rx_cnt = 0;
  g_esp_rx_drop = 0;
}

/* 供 USART3_IRQHandler 调用：写入一个字节（满则丢弃） */
void ESP_UART_RX_ISR(uint8_t b)
{
  uint16_t next = (uint16_t)((rx_head + 1U) % ESP_RX_BUF_LEN);
  g_esp_rx_cnt++;
  if (next != rx_tail)
  {
    rx_buf[rx_head] = b;
    rx_head = next;
  }
  else
  {
    g_esp_rx_drop++;      /* 缓冲满：说明主循环没及时取走，正常工作时应为 0 */
  }
}

static uint16_t rx_avail(void)
{
  uint16_t h = rx_head;
  if (h >= rx_tail) return (uint16_t)(h - rx_tail);
  return (uint16_t)(ESP_RX_BUF_LEN - rx_tail + h);
}

/**
  * @brief USART3 中断服务函数（覆盖启动文件里的弱定义 Default_Handler）
  *
  * @note  这里直接读 SR/DR，不调用 HAL_UART_IRQHandler —— 本模块用自建环形缓冲，
  *        不走 HAL 的接收状态机，更简单也更可控。
  *        ⚠️ RXNE 与 ORE 都**必须靠"读 DR"清除**；ORE（溢出）不清掉的话，
  *           此后 RXNE 不再置位，表现为"串口突然收不到任何数据"。
  *        ⚠️ 若以后在 CubeMX 里勾选了 "USART3 global interrupt"，
  *           stm32f1xx_it.c 会生成同名的 USART3_IRQHandler，与本函数重复定义；
  *           到时二选一：删掉本函数（改在 it.c 里调 ESP_UART_RX_ISR），或反过来。
  */
void USART3_IRQHandler(void)
{
  uint32_t sr = USART3->SR;

  if ((sr & (USART_SR_RXNE | USART_SR_ORE)) != 0U)
  {
    uint8_t b = (uint8_t)(USART3->DR & 0xFFU);   /* 读 DR：同时清 RXNE 与 ORE */

    if ((sr & USART_SR_RXNE) != 0U)
    {
      ESP_UART_RX_ISR(b);
    }
  }
}

uint16_t ESP_Peek(uint8_t *out, uint16_t maxlen)
{
  uint16_t n = rx_avail();
  if (n > maxlen) n = maxlen;
  for (uint16_t i = 0; i < n; i++)
  {
    out[i] = rx_buf[(rx_tail + i) % ESP_RX_BUF_LEN];
  }
  return n;
}

void ESP_Consume(uint16_t c)
{
  uint16_t n = rx_avail();
  if (c > n) c = n;
  rx_tail = (uint16_t)((rx_tail + c) % ESP_RX_BUF_LEN);
}

void ESP_Drain(void)
{
  rx_tail = rx_head;
}

bool ESP_WaitFor(const char *kw, uint32_t timeout_ms)
{
  uint32_t t0 = HAL_GetTick();
  /* 多留 1 字节给结尾的 '\0'，防止越界写 */
  static uint8_t tmp[ESP_RX_BUF_LEN + 1];
  const uint16_t cap = ESP_RX_BUF_LEN;

  while ((HAL_GetTick() - t0) < timeout_ms)
  {
    /* ESP_Peek 返回实际拷贝字节数（不消费）；必须用返回值截断字符串，
       否则 tmp 尾部未初始化数据会被 strstr 扫到，AT 响应匹配不可靠。 */
    uint16_t n = ESP_Peek(tmp, cap);
    if (n > 0)
    {
      tmp[n] = 0;
      if (strstr((char *)tmp, kw) != NULL)
        return true;
    }
    HAL_Delay(20);
  }
  return false;
}

bool ESP_SendAT(const char *cmd, const char *expect, uint32_t timeout_ms)
{
  ESP_Drain();
  uint16_t len = (uint16_t)strlen(cmd);
  HAL_UART_Transmit(&huart3, (uint8_t *)cmd, len, 200);
  HAL_UART_Transmit(&huart3, (uint8_t *)"\r\n", 2, 50);
  bool ok = ESP_WaitFor(expect, timeout_ms);
  ESP_Drain();
  return ok;
}

void ESP_SendRaw(const uint8_t *data, uint16_t len)
{
  HAL_UART_Transmit(&huart3, (uint8_t *)data, len, 1000);
}

bool ESP_ATReady(void)
{
  if (!ESP_SendAT("AT", "OK", 2000))
    return false;
  ESP_SendAT("ATE0", "OK", 1000);   /* 关闭回显，失败不致命 */
  return true;
}

void ESP_ExitTransparent(void)
{
  /* 透传模式下先发 "+++"（无换行）退出，再等待静默期 */
  HAL_UART_Transmit(&huart3, (uint8_t *)"+++", 3, 50);
  HAL_Delay(1000);
  ESP_Drain();
}

/**
  * @brief 连 WiFi（带错误码捕获）
  * @note  把模块回的 "+CWJAP:<n>" 存进 g_esp_last_cwjap_err 并记进 OLED 事件日志 ——
  *        这是排查"连不上热点"最直接的线索。
  *        失败时一收到 FAIL 就立刻返回，不必等满超时。
  */
bool ESP_JoinWiFi(const char *ssid, const char *pwd)
{
  char     cmd[128];
  uint8_t  buf[128];
  uint32_t t0;
  bool     got_ok   = false;
  bool     got_fail = false;
  int      n;

  g_esp_last_cwjap_err = -1;

  if (!ESP_SendAT("AT+CWMODE=1", "OK", 2000))
  {
    UI_Log("WIFI:CWMODE FAIL");
    return false;
  }

  n = snprintf(cmd, sizeof(cmd), "AT+CWJAP=\"%s\",\"%s\"", ssid, pwd);
  if (n < 0 || n >= (int)sizeof(cmd))
  {
    return false;
  }

  ESP_Drain();
  HAL_UART_Transmit(&huart3, (uint8_t *)cmd, (uint16_t)strlen(cmd), 200);
  HAL_UART_Transmit(&huart3, (uint8_t *)"\r\n", 2, 50);

  /* 成功：WIFI CONNECTED / WIFI GOT IP / OK
     失败：+CWJAP:<1..4> 后跟 FAIL  —— 收到任意一个就结束 */
  t0 = HAL_GetTick();
  while ((HAL_GetTick() - t0) < 25000U)
  {
    uint16_t rn = ESP_Peek(buf, sizeof(buf) - 1U);
    if (rn > 0U)
    {
      char *p;

      buf[rn] = 0;

      if (strstr((char *)buf, "OK") != NULL)
      {
        got_ok = true;
      }
      if (strstr((char *)buf, "FAIL") != NULL)
      {
        got_fail = true;
      }
      p = strstr((char *)buf, "+CWJAP:");
      if (p != NULL)
      {
        g_esp_last_cwjap_err = atoi(p + 7);
      }
      ESP_Consume(rn);
    }
    if (got_ok || got_fail)
    {
      break;
    }
    HAL_Delay(50);
  }

  if (got_ok)
  {
    UI_Log("WIFI OK");
    return true;
  }

  /* 失败：把错误码翻译成人能看懂的原因（也留在 g_esp_last_cwjap_err 里） */
  switch (g_esp_last_cwjap_err)
  {
    case 1:  UI_Log("WIFI ERR:TIMEOUT");   break;   /* 连接超时，多半信号弱 */
    case 2:  UI_Log("WIFI ERR:PASSWORD");  break;   /* 密码错 */
    case 3:  UI_Log("WIFI ERR:NO AP");     break;   /* 找不到 AP：5GHz / 名称错 / 太远 */
    case 4:  UI_Log("WIFI ERR:CONN FAIL"); break;   /* 不兼容（如 WPA3）或 AP 满 */
    default: UI_Log("WIFI ERR:NO REPLY");  break;   /* 一条响应都没收到 */
  }
  return false;
}

/**
  * @brief 扫描附近热点，判断目标 SSID 是否可见
  * @note  边收边查 + 每次只消费前面部分、保留尾部 32 字节：
  *        既能避免 AT+CWLAP 的长响应冲爆 512 字节环形缓冲，
  *        也避免关键字恰好落在两次读取的边界上而漏判。
  */
bool ESP_ScanForSSID(const char *ssid)
{
  uint8_t  buf[160];
  char     pat[40];
  uint32_t t0;
  bool     found = false;
  bool     done  = false;

  (void)snprintf(pat, sizeof(pat), "\"%s\"", ssid);   /* 带引号匹配，避免误命中 */

  ESP_Drain();
  HAL_UART_Transmit(&huart3, (uint8_t *)"AT+CWLAP\r\n",
                    (uint16_t)(sizeof("AT+CWLAP\r\n") - 1U), 200);

  t0 = HAL_GetTick();
  while ((!done) && ((HAL_GetTick() - t0) < 12000U))
  {
    uint16_t n = ESP_Peek(buf, sizeof(buf) - 1U);
    if (n > 0U)
    {
      buf[n] = 0;
      if (strstr((char *)buf, pat) != NULL)
      {
        found = true;
        done  = true;
      }
      if (strstr((char *)buf, "OK") != NULL)
      {
        done = true;                       /* 扫描结束 */
      }
      ESP_Consume((n > 32U) ? (uint16_t)(n - 32U) : 0U);
    }
    HAL_Delay(50);
  }

  ESP_Drain();
  return found;
}

bool ESP_ConnectTCP(const char *host, uint16_t port)
{
  char cmd[64];

  /* 🔴 先关掉可能残留的 TCP 连接。
     重连时如果旧连接还在，`AT+CIPSTART` 会回 `ALREADY CONNECTED`，
     而旧代码把它当成"连上了"，于是我们在**同一条 TCP 连接上又发一次 MQTT CONNECT**
     —— MQTT 规定一条连接只能 CONNECT 一次，平台直接以协议错误断开
     （OneNET 侧记录 offline_reason = CloseDueToProtoError）。
     连接不存在时 CIPCLOSE 会回 ERROR，忽略即可。 */
  ESP_SendAT("AT+CIPCLOSE", "OK", 1000);
  ESP_SendAT("AT+CIPMUX=0", "OK", 2000);

  snprintf(cmd, sizeof(cmd), "AT+CIPSTART=\"TCP\",\"%s\",%u", host, (unsigned)port);
  if (ESP_SendAT(cmd, "OK", 8000))
    return true;

  /* 走到这里说明没拿到 OK。若现场是 ALREADY CONNECTED，
     **必须关掉旧连接后重连一次**，绝不能顺着旧 socket 继续发 CONNECT。 */
  if (ESP_WaitFor("ALREADY CONNECTED", 1000))
  {
    ESP_Drain();
    ESP_SendAT("AT+CIPCLOSE", "OK", 1000);
    ESP_SendAT("AT+CIPMUX=0", "OK", 2000);
    return ESP_SendAT(cmd, "OK", 8000);   /* 只重试一次，避免死循环 */
  }

  /* 其它情况（DNS 失败 / 无网络 / 服务器拒绝）一律算失败，交给上层重来 */
  ESP_Drain();
  return false;
}

bool ESP_EnterTransparent(void)
{
  if (!ESP_SendAT("AT+CIPMODE=1", "OK", 2000))
    return false;
  ESP_Drain();
  /* 🔴 长度必须正好 12（"AT+CIPSEND\r\n"）。
     原来写的是 13 —— 多发了字符串末尾的 `\0`。这个 0x00 一旦赶在模块切入透传之后
      到达，就会被当成**第一个 TCP 数据字节**发出去，MQTT 流开头多一个 0x00
      ⇒ 平台解析到非法报文类型 ⇒ 断开并记录 `CloseDueToProtoError`。
      是否出事取决于时序（0x00 落在命令模式还是透传模式），所以表现为"有时能连、连上过一会又掉"。 */
  HAL_UART_Transmit(&huart3, (uint8_t *)"AT+CIPSEND\r\n",
                    (uint16_t)(sizeof("AT+CIPSEND\r\n") - 1U), 200);
  /* 透传模式开启后会回 '>'，之后所有字节直发 TCP */
  if (!ESP_WaitFor(">", 3000))
    return false;
  ESP_Drain();

  /* 🔴 '>' 之后模块还可能补吐一个 "\r\n"（不同 AT 固件行为不一致）。
     这一段若残留、并在进入透传后被当成 TCP 数据发出去，MQTT 流开头就多两个
     垃圾字节 ⇒ 平台解析到非法报文 ⇒ 同样是 CloseDueToProtoError。
     所以先静置 100 ms 让尾巴吐完，再清一次。**这段延时只在建链时发生一次**。 */
  HAL_Delay(100);
  ESP_Drain();
  return true;
}
