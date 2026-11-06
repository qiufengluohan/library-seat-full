/**
  ******************************************************************************
  * @file    onenet.c
  * @brief   OneNET 物模型对接实现
  *
  * @note    ⚠️ 栈优化（2026-10-14）：
  *          用 GCC 的 -fstack-usage 实测过，本文件原来几个大缓冲都放在**栈上**：
  *            ONENET_Connect        864 B
  *            ONENET_ProcessIncoming 808 B   ← 加上调用者已接近 1KB 的栈上限！
  *            publish_json          424 B
  *            ONENET_PublishProperty 416 B
  *          而链接脚本里 `_Min_Stack_Size` 只有 **1024 B**（见下一段注释）。
  *          现在把 pkt[]/tmp[]/json[]/head[]/id[]/pay[]/topic[]/clientId[] 全部改成
  *          **函数内 static**（不占栈，进 .bss）⇒ 上述链路的栈需求降到约 30~140 B。
  *
  *          ⚠️ 代价：这些函数不可重入。本工程是单线程主循环调用，且**不在中断里调用**它们，
  *             所以安全。若将来要放进中断或任务，必须改回局部变量或改用互斥。
  *
  * @note    另外顺手修掉一个 1 字节越界：下行解析原为
  *            `uint8_t pay[256]; uint16_t plen = sizeof(pay);`
  *          而 MQTT_ExtractPublish() 会把 plen 截断到 *pay_len（=256），
  *          随后那句 `pay[plen] = 0;` 就写到了 pay[256] —— 越界 1 字节。
  *          现在把上限改成 `sizeof(pay) - 1`，给结尾的 '\0' 留出位置。
  ******************************************************************************
  */

#include "onenet.h"
#include "mqtt_client.h"
#include "esp8266.h"
#include "app_config.h"
#include "seat_app.h"
#include "logging.h"
#include "ui.h"

#include <string.h>
#include <stdio.h>
#include <stdlib.h>

static uint32_t g_msg_id = 1;

/** 对端（平台/路由器）主动断链的标志，由 seat_app 读取后立即重连 */
static bool g_link_lost = false;

bool ONENET_IsLinkLost(void)   { return g_link_lost; }
void ONENET_ClearLinkLost(void){ g_link_lost = false; }

/* 把一段 JSON 作为 PUBLISH 载荷发出 */
static void publish_json(const char *topic, const char *json)
{
  static uint8_t pkt[MQTT_BUF_LEN];      /* static：不占栈 */
  uint16_t plen;

  MQTT_BuildPublish(pkt, &plen, topic,
                    (const uint8_t *)json, (uint16_t)strlen(json), 0);
  ESP_SendRaw(pkt, plen);
}

/* 构造 OneJSON 公共头 */
static void json_header(char *buf, size_t size, char *id_out)
{
  snprintf(buf, size, "{\"id\":\"%lu\",\"version\":\"1.0\"",
           (unsigned long)g_msg_id);
  snprintf(id_out, 16, "%lu", (unsigned long)g_msg_id);
  g_msg_id++;
}

bool ONENET_Connect(void)
{
  static uint8_t pkt[MQTT_BUF_LEN];
  static uint8_t tmp[MQTT_BUF_LEN];
  static char    clientId[64];
  uint16_t plen;
  uint32_t t0;

  /* ⚠️ Studio 实例的 CONNECT 三要素（官方文档 + 本项目 02-OneNET接入与物模型设计.md）：
       clientId = **设备名称**（不是 "产品ID.设备名"，那是旧版标准版的写法）
       username = 产品 ID
       password = 鉴权 token
     若将来发现平台不认，可临时换回 "%s.%s" 的旧格式试一次 —— 但先按官方文档来。 */
  snprintf(clientId, sizeof(clientId), "%s", DEVICE_NAME);

  MQTT_BuildConnect(pkt, &plen, clientId, ONENET_PRODUCT_ID,
                    ONENET_DEVICE_TOKEN, 120);
  ESP_SendRaw(pkt, plen);

  t0 = HAL_GetTick();
  while ((HAL_GetTick() - t0) < 8000)
  {
    uint16_t n = ESP_Peek(tmp, sizeof(tmp));
    if (n >= 4)
    {
      uint16_t consumed;
      uint8_t type;
      pkt_status_t st = MQTT_PeekPacket(tmp, n, &consumed, &type);
      if (st == PKT_COMPLETE)
      {
        if (type == MQTT_PKT_CONNACK)
        {
          bool ok = (n >= 4 && tmp[3] == 0x00);   /* rc=0 成功 */
          ESP_Consume(consumed);
          LOG_Str(ok ? "[ONENET] CONNACK ok\r\n" : "[ONENET] CONNACK fail\r\n");
          return ok;
        }
        ESP_Consume(consumed);   /* 其它报文（如 PINGRESP）直接消费 */
      }
      else if (st == PKT_NONE)
      {
        break;
      }
    }
    HAL_Delay(50);
  }
  LOG_Str("[ONENET] CONNACK timeout\r\n");
  return false;
}

void ONENET_SubscribeDownlink(void)
{
  static uint8_t  pkt[MQTT_BUF_LEN];
  static uint8_t  tmp[MQTT_BUF_LEN];
  static uint16_t sub_id = 1;
  uint16_t plen;
  uint32_t t0;

  MQTT_BuildSubscribe(pkt, &plen, sub_id++, TOPIC_DOWNLINK, 0);
  ESP_SendRaw(pkt, plen);
  LOG_Str("[ONENET] subscribed downlink\r\n");

  /* 🔴 等 SUBACK，并把结果写进 OLED 事件日志（2026-10-19 加）。
     以前发完就当订阅成功、直接显示 `== ONLINE ==` —— 但"平台把连接断了"
     和"订阅成功"在设备端看起来一模一样，等于把最关键的证据丢了。

     SUBACK 报文：`0x90 0x03 <id_hi> <id_lo> <返回码>`
       返回码 0x00 = 成功；0x80 = 该主题不被允许（主题写错/产品不匹配）。
     3 秒内连一个字节都等不到 ⇒ 多半是平台已经断开（协议错误 / 鉴权失败），
     屏上显示 `SUB TIMEOUT`，配合平台侧的 offline_reason 就能对上号。 */
  t0 = HAL_GetTick();
  while ((HAL_GetTick() - t0) < 3000U)
  {
    uint16_t n = ESP_Peek(tmp, sizeof(tmp));

    if (n >= 5U)
    {
      uint16_t consumed;
      uint8_t  type;

      if (MQTT_PeekPacket(tmp, n, &consumed, &type) == PKT_COMPLETE)
      {
        if (type == MQTT_PKT_SUBACK)
        {
          uint8_t rc = tmp[consumed - 1U];   /* 最后一个字节是返回码 */
          ESP_Consume(consumed);
          if (rc == 0x00U)
          {
            UI_Log("SUBACK OK");
          }
          else
          {
            UI_Log("SUBACK ERR %02X", (unsigned)rc);
          }
          return;
        }
        ESP_Consume(consumed);               /* 其它报文先消费掉 */
      }
      else
      {
        break;                               /* 报文头就不合法，别死等 */
      }
    }
    HAL_Delay(20);
  }

  UI_Log("SUB TIMEOUT");
}

#if (SEAT_COUNT != 2U)
#error "ONENET_PublishProperty 的报文是按两个座位手写的，改 SEAT_COUNT 要同步改这里"
#endif

void ONENET_PublishProperty(const uint16_t pressure[SEAT_COUNT],
                            const bool     pir[SEAT_COUNT],
                            const bool     alarm[SEAT_COUNT])
{
  static char head[48];
  static char id[16];
  /**
   * @note  缓冲 384（V17 从 288 提上来）：一包 6 个参数的载荷约 230~290 B，
   *        288 太紧；同时 MQTT_BUF_LEN 也是 384，整条 PUBLISH 报文约 281 B，装得下。
   */
  static char json[384];

  json_header(head, sizeof(head), id);

  /* 一包两座位（docs/05 §2.3）：6 个参数的标识符必须与云端物模型逐字一致。
     注意两点（对齐 docs/02-OneNET接入与物模型设计.md §2.1）：
     ① **不带 time 字段** —— OneJSON 里 time 是可选的，省略后平台用接收时间；
        以前塞的是 `HAL_GetTick()`（开机毫秒数），在平台上会变成 1970 年的时间戳。
     ② `pir_state_x` / `alarm_flag_x` 是 **bool**，要发 true/false，不能发 1/0。 */
  (void)snprintf(json, sizeof(json),
    "%s,\"params\":{"
      "\"pressure_adc_1\":{\"value\":%u},"
      "\"pressure_adc_2\":{\"value\":%u},"
      "\"pir_state_1\":{\"value\":%s},"
      "\"pir_state_2\":{\"value\":%s},"
      "\"alarm_flag_1\":{\"value\":%s},"
      "\"alarm_flag_2\":{\"value\":%s}"
    "}}",
    head,
    (unsigned)pressure[0], (unsigned)pressure[1],
    pir[0]   ? "true" : "false",
    pir[1]   ? "true" : "false",
    alarm[0] ? "true" : "false",
    alarm[1] ? "true" : "false");

  publish_json(TOPIC_PROP_POST, json);
}

void ONENET_PublishFakeOccupy(uint8_t seat_no, uint16_t pressure, bool pir)
{
  static char head[48];
  static char id[16];
  static char json[384];

  json_header(head, sizeof(head), id);

  /* 🔴 事件上报的 topic 不带标识符，标识符放 params 里；
     物模型的 5 个输出参数（pressure_adc / pir_state / device_id / timestamp / seat_no）
     **一个都不能少** —— 少一个平台回 code=2308 event param count error。
     原来的写法（topic 带 fake_occupy、params 平铺压力值）会让平台**直接断链**，
     设备表现为"一触发告警就离线"。见 app_config.h 的 TOPIC_EVENT_POST 注释。

     seat_no 是 V17 新增：后端靠它把告警落到 (device_id, seat_no) 那一行，
     不带的话两个座位的告警会混在同一个设备上分不清。 */
  (void)snprintf(json, sizeof(json),
    "%s,\"params\":{\"fake_occupy\":{\"value\":{"
      "\"pressure_adc\":%u,"
      "\"pir_state\":%s,"
      "\"device_id\":\"%s\","
      "\"timestamp\":0,"
      "\"seat_no\":%u"
    "}}}}",
    head,
    (unsigned)pressure,
    pir ? "true" : "false",
    DEVICE_NAME,
    (unsigned)seat_no);

  publish_json(TOPIC_EVENT_POST, json);
}

void ONENET_PublishRfid(const char *uid_hex)
{
  static char head[48];
  static char id[16];
  static char json[288];

  json_header(head, sizeof(head), id);

  /* rfid_scan 的 3 个输出参数：rfid_uid / device_id / timestamp，同样必须齐全 */
  snprintf(json, sizeof(json),
    "%s,\"params\":{\"rfid_scan\":{\"value\":{"
      "\"rfid_uid\":\"%s\","
      "\"device_id\":\"%s\","
      "\"timestamp\":0"
    "}}}}",
    head, uid_hex, DEVICE_NAME);

  publish_json(TOPIC_EVENT_POST, json);
}

void ONENET_Ping(void)
{
  static uint8_t pkt[MQTT_BUF_LEN];
  uint16_t plen;

  MQTT_BuildPing(pkt, &plen);
  ESP_SendRaw(pkt, plen);
}

void ONENET_ProcessIncoming(void)
{
  static uint8_t tmp[MQTT_BUF_LEN];
  static char    topic[96];
  static uint8_t pay[256];
  static char    svc[32];

  for (;;)
  {
    uint16_t n = ESP_Peek(tmp, sizeof(tmp));
    if (n == 0) break;

    /* 🔴 透传模式下模块会往串口吐 ASCII 状态行：`\r\nCLOSED\r\n`、`WIFI DISCONNECT`。
       这些字节必须先认出来 —— 否则 'C'(0x43) 会被 MQTT 解析器当成 PUBACK 报文头，
       然后一直等一个永远凑不齐的剩余长度，设备要等 6 分钟的 pong 超时才重连。
       只在缓冲区最前面 16 字节里找，避免把 JSON 正文里的同名子串误判。 */
    {
      char     probe[17];
      uint16_t pn = (n < 16U) ? n : 16U;

      memcpy(probe, tmp, pn);
      probe[pn] = 0;
      if ((strstr(probe, "CLOSED") != NULL) || (strstr(probe, "WIFI DISCONNECT") != NULL))
      {
        g_link_lost = true;
        ESP_Drain();
        return;
      }
    }

    uint16_t consumed;
    uint8_t type;
    pkt_status_t st = MQTT_PeekPacket(tmp, n, &consumed, &type);

    if (st == PKT_COMPLETE)
    {
      if (type == MQTT_PKT_PUBLISH)
      {
        /* 上限给 sizeof(pay)-1，留 1 字节给后面的 '\0'（原实现会越界 1 字节） */
        uint16_t plen = (uint16_t)(sizeof(pay) - 1U);

        if (MQTT_ExtractPublish(tmp, n, topic, sizeof(topic), pay, &plen))
        {
          pay[plen] = 0;
          if (strstr(topic, "property/set") != NULL)
          {
            SEAT_OnPropertySet((char *)pay);
          }
          else if (strstr(topic, "service/") != NULL)
          {
            const char *a = strstr(topic, "service/");
            if (a != NULL)
            {
              a += 8;  /* skip "service/" */
              const char *b = strstr(a, "/invoke");
              int l = (b != NULL) ? (int)(b - a) : (int)strlen(a);
              if (l > 31) l = 31;
              memcpy(svc, a, (size_t)l);
              svc[l] = 0;
              SEAT_OnServiceInvoke(svc, (char *)pay);
            }
          }
          else if (strstr(topic, "post/reply") != NULL)
          {
            /* OneNET 对属性/事件上报的应答：{"id":"1","code":200,"msg":"success"}
               code≠200 说明物模型标识符写错、属性权限不对、或产品/设备不匹配。
               ⚠️ 以前这里直接丢弃，于是"上报成功"和"被平台拒绝"在设备端看起来一模一样 ——
               联调时这是最需要看见的一条信息。 */
            const char *c = strstr((char *)pay, "\"code\":");
            if (c != NULL)
            {
              int code = atoi(c + 7);
              if (code != 200)
              {
                UI_Log("PUB ERR %d", code);
                LOG_Str("[ONENET] post rejected\r\n");
              }
            }
          }
        }
      }
      else if (type == MQTT_PKT_PINGRESP)
      {
        SEAT_OnPingResp();   /* 刷新保活计时，防止误判掉线 */
      }
      ESP_Consume(consumed);
    }
    else if (st == PKT_INCOMPLETE)
    {
      break;   /* 报文未收全，等下次 */
    }
    else
    {
      break;
    }
  }
}
