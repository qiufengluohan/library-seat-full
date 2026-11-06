/**
  ******************************************************************************
  * @file    mqtt_client.h
  * @brief   极简 MQTT 3.1.1 客户端（仅实现本工程需要的报文）
  *           - CONNECT / CONNACK
  *           - PUBLISH (QoS0) / 下行 PUBLISH 解析
  *           - SUBSCRIBE (QoS0) / SUBACK（仅消费）
  *           - PINGREQ / PINGRESP（仅消费）
  * @note    不依赖任何第三方库，报文全部手搓，适配 ESP8266 透传。
  *          本文件为纯字节操作，与 MCU 引脚、外设无关，可直接复用。
  ******************************************************************************
  */

#ifndef __MQTT_CLIENT_H
#define __MQTT_CLIENT_H

#include <stdint.h>
#include <stdbool.h>

/**
 * @brief MQTT 报文缓冲长度（**实测**：属性上报 211B；补齐物模型 5 属性需 323B）
 * @note  ⚠️ 不要改小！mqtt_client.c 里有 `#if (MQTT_BUF_LEN < 384) #error` 兜底。
 */
#define MQTT_BUF_LEN  384

/* 报文类型（高 4 位） */
#define MQTT_PKT_CONNECT    0x10
#define MQTT_PKT_CONNACK    0x20
#define MQTT_PKT_PUBLISH    0x30
#define MQTT_PKT_SUBSCRIBE  0x80
#define MQTT_PKT_SUBACK     0x90
#define MQTT_PKT_PINGREQ    0xC0
#define MQTT_PKT_PINGRESP   0xD0

typedef enum {
  PKT_NONE = 0,        /* 缓冲不足，连 2 字节都没有 */
  PKT_COMPLETE = 1,    /* 已收到一个完整报文 */
  PKT_INCOMPLETE = 2   /* 报文未收全，需继续等待 */
} pkt_status_t;

/* 构造 CONNECT（clean session=1，带 username/password） */
void MQTT_BuildConnect(uint8_t *out, uint16_t *outlen,
                       const char *clientId, const char *user,
                       const char *pass, uint16_t keepalive);

/* 构造 PUBLISH（QoS0，retain=0，dup=0） */
void MQTT_BuildPublish(uint8_t *out, uint16_t *outlen,
                       const char *topic, const uint8_t *payload,
                       uint16_t paylen, uint8_t qos);

/* 构造 SUBSCRIBE（QoS0） */
void MQTT_BuildSubscribe(uint8_t *out, uint16_t *outlen,
                          uint16_t pktid, const char *topic, uint8_t qos);

/* 构造 PINGREQ（0xC0 0x00） */
void MQTT_BuildPing(uint8_t *out, uint16_t *outlen);

/**
  * @brief  从 buf 起始处尝试解析一个 MQTT 报文
  * @param  consumed   完整报文占用字节数（COMPLETE 时有效）
  * @param  type_out   报文类型高 4 位（COMPLETE 时有效）
  * @retval PKT_COMPLETE / PKT_INCOMPLETE / PKT_NONE
  */
pkt_status_t MQTT_PeekPacket(const uint8_t *buf, uint16_t len,
                             uint16_t *consumed, uint8_t *type_out);

/**
  * @brief  从已确认完整的 PUBLISH 报文中抽取主题与载荷
  * @note   仅当 MQTT_PeekPacket 返回的 type==MQTT_PKT_PUBLISH 时调用
  */
bool MQTT_ExtractPublish(const uint8_t *buf, uint16_t len,
                         char *topic_out, uint16_t topic_max,
                         uint8_t *pay_out, uint16_t *pay_len);

#endif /* __MQTT_CLIENT_H */
