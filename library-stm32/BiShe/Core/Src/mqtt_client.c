/**
  ******************************************************************************
  * @file    mqtt_client.c
  * @brief   MQTT 3.1.1 报文构造与解析（极简实现）
  *
  * @note    ⚠️ 缓冲区加固（2026-10-14）：
  *          原来 BuildConnect/BuildPublish/BuildSubscribe 内部各用一个**栈上**临时数组
  *          （var[256] / var[240] / var[240]）。实测"属性上报"报文已达 211 字节，
  *          BuildPublish 的 var[240] 里实际写入 208 字节（**只剩 32B 余量**）；
  *          时间戳涨到 10 位（开机约 11.6 天后）会再多 12B；
  *          若按物模型补齐 5 个属性则需要 320B ⇒ **var[240] 溢出 80 字节，会踩坏栈**。
  *          现在统一改成 `static uint8_t var[MQTT_BUF_LEN]`：
  *            ① 尺寸与输出缓冲一致，天然不会溢出（写入量 = body ≤ MQTT_BUF_LEN - 3）；
  *            ② static 不占栈 —— F103 只有 20KB RAM，栈很宝贵。
  *          ⚠️ 代价：这三个函数不可重入。本工程只在主循环里调用、不在中断里用，安全。
  ******************************************************************************
  */

#include "mqtt_client.h"
#include <string.h>

/* 编译期校验：输出缓冲被改小到不够用时直接报错（实测需 ≥ 384） */
#if (MQTT_BUF_LEN < 384)
#error "MQTT_BUF_LEN 太小：属性上报实测 211B，补齐物模型 5 属性需 323B，请保持 >= 384"
#endif

/* 可变长度编码 remaining length（最大 4 字节） */
static uint8_t enc_remlen(uint32_t len, uint8_t *out)
{
  uint8_t n = 0;
  do {
    uint8_t b = (uint8_t)(len % 128U);
    len /= 128U;
    if (len > 0) b |= 0x80U;
    out[n++] = b;
  } while (len > 0 && n < 4);
  return n;
}

/* 写入 2 字节大端长度 + 字符串 */
static uint16_t put_str(uint8_t *p, const char *s)
{
  uint16_t L = (uint16_t)strlen(s);
  *p++ = (uint8_t)(L >> 8);
  *p++ = (uint8_t)(L & 0xFF);
  memcpy(p, s, L);
  return (uint16_t)(L + 2);
}

void MQTT_BuildConnect(uint8_t *out, uint16_t *outlen,
                       const char *clientId, const char *user,
                       const char *pass, uint16_t keepalive)
{
  static uint8_t var[MQTT_BUF_LEN];
  uint16_t vi = 0;

  /* 协议名 "MQTT" */
  var[vi++] = 0x00; var[vi++] = 0x04;
  var[vi++] = 'M'; var[vi++] = 'Q'; var[vi++] = 'T'; var[vi++] = 'T';
  var[vi++] = 0x04;                 /* 协议级别 3.1.1 */
  var[vi++] = 0xC2;                 /* 标志：username+password+clean session */
  var[vi++] = (uint8_t)(keepalive >> 8);
  var[vi++] = (uint8_t)(keepalive & 0xFF);

  /* 载荷：clientId / user / pass */
  vi += put_str(var + vi, clientId);
  vi += put_str(var + vi, user);
  vi += put_str(var + vi, pass);

  out[0] = MQTT_PKT_CONNECT;
  uint8_t rl[4];
  uint8_t rln = enc_remlen(vi, rl);
  uint16_t o = 1;
  memcpy(out + o, rl, rln); o += rln;
  memcpy(out + o, var, vi);  o += vi;
  *outlen = o;
}

void MQTT_BuildPublish(uint8_t *out, uint16_t *outlen,
                       const char *topic, const uint8_t *payload,
                       uint16_t paylen, uint8_t qos)
{
  static uint8_t var[MQTT_BUF_LEN];
  uint16_t vi = 0;

  vi += put_str(var + vi, topic);
  if (qos > 0)
  {
    var[vi++] = 0x00; var[vi++] = 0x01;   /* 包标识（占位） */
  }
  memcpy(var + vi, payload, paylen);
  vi += paylen;

  out[0] = (uint8_t)(MQTT_PKT_PUBLISH | (qos << 1));
  uint8_t rl[4];
  uint8_t rln = enc_remlen(vi, rl);
  uint16_t o = 1;
  memcpy(out + o, rl, rln); o += rln;
  memcpy(out + o, var, vi);  o += vi;
  *outlen = o;
}

void MQTT_BuildSubscribe(uint8_t *out, uint16_t *outlen,
                         uint16_t pktid, const char *topic, uint8_t qos)
{
  static uint8_t var[MQTT_BUF_LEN];
  uint16_t vi = 0;

  var[vi++] = (uint8_t)(pktid >> 8);
  var[vi++] = (uint8_t)(pktid & 0xFF);
  vi += put_str(var + vi, topic);
  var[vi++] = qos & 0xFF;

  /* 🔴 固定头必须是 **0x82**，不是 0x80。
     MQTT 3.1.1 [MQTT-3.8.1-1]：SUBSCRIBE 固定头的低 4 位是**保留标志**，
     硬性规定为 0b0010，服务器收到其它值必须按「畸形报文」处理并**断开网络连接**。
     `MQTT_PKT_SUBSCRIBE` 只是报文类型 0x80，必须再或上 0x02 才是合法的 0x82。
     2026-10-19 前的代码直接写 0x80 ⇒ 连上后发出的第一个 SUBSCRIBE 就被 OneNET
     判为协议错误，平台立刻断开并记录 `offline_reason = CloseDueToProtoError`
     —— 现象就是「刚显示 ONLINE 就掉线、一直连不上云端」。 */
  out[0] = (uint8_t)(MQTT_PKT_SUBSCRIBE | 0x02);
  uint8_t rl[4];
  uint8_t rln = enc_remlen(vi, rl);
  uint16_t o = 1;
  memcpy(out + o, rl, rln); o += rln;
  memcpy(out + o, var, vi);  o += vi;
  *outlen = o;
}

void MQTT_BuildPing(uint8_t *out, uint16_t *outlen)
{
  out[0] = MQTT_PKT_PINGREQ;       /* 0xC0 */
  out[1] = 0x00;
  *outlen = 2;
}

pkt_status_t MQTT_PeekPacket(const uint8_t *buf, uint16_t len,
                             uint16_t *consumed, uint8_t *type_out)
{
  if (len < 2) return PKT_NONE;

  uint8_t first = buf[0];
  /* PINGRESP 固定 2 字节 */
  if (first == MQTT_PKT_PINGRESP)
  {
    if (len >= 2) { *consumed = 2; *type_out = MQTT_PKT_PINGRESP; return PKT_COMPLETE; }
    return PKT_INCOMPLETE;
  }

  /* 解析 remaining length（变长） */
  uint32_t rem = 0;
  uint8_t m = 1;
  uint8_t shift = 0;
  while (m < len)
  {
    uint8_t b = buf[m];
    rem |= (uint32_t)(b & 0x7FU) << shift;
    shift += 7;
    m++;
    if ((b & 0x80U) == 0) break;
    if (shift > 21) return PKT_NONE;   /* 畸形 */
  }
  if (m > len) return PKT_NONE;

  uint32_t total = (uint32_t)m + rem;   /* 固定头(控制字节+剩余长度) + 剩余长度 */
  if (total > 0xFFFF) return PKT_NONE;
  if (total > len) return PKT_INCOMPLETE;

  *consumed = (uint16_t)total;
  *type_out = first & 0xF0;
  return PKT_COMPLETE;
}

bool MQTT_ExtractPublish(const uint8_t *buf, uint16_t len,
                        char *topic_out, uint16_t topic_max,
                        uint8_t *pay_out, uint16_t *pay_len)
{
  if (len < 2) return false;
  uint8_t qos = (buf[0] >> 1) & 0x03;

  /* 跳过 remaining length */
  uint8_t m = 1;
  while (m < len && (buf[m] & 0x80U)) m++;
  if (m >= len) return false;
  m++;  /* 指向可变头起点 */

  if (m + 2 > len) return false;
  uint16_t tlen = (uint16_t)((buf[m] << 8) | buf[m + 1]);
  m += 2;
  if (m + tlen > len) return false;

  uint16_t cpy = (tlen < topic_max - 1) ? tlen : (topic_max - 1);
  for (uint16_t i = 0; i < cpy; i++)
    topic_out[i] = (char)buf[m + i];
  topic_out[cpy] = 0;
  m += tlen;

  if (qos > 0) m += 2;            /* 跳过包标识 */

  uint16_t pstart = m;
  uint16_t plen = (uint16_t)(len - pstart);
  if (plen > *pay_len) plen = *pay_len;
  memcpy(pay_out, buf + pstart, plen);
  *pay_len = plen;
  return true;
}
