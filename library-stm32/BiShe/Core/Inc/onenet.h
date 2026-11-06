/**
  ******************************************************************************
  * @file    onenet.h
  * @brief   OneNET 物模型对接：属性上行 / 事件上行 / 下行订阅与分发
  * @note    报文格式严格遵循 OneJSON：
  *           上行属性：$sys/{pid}/{dev}/thing/property/post
  *           上行事件：$sys/{pid}/{dev}/thing/event/post（标识符放 params 里）
  *           下行：    thing/# 通配订阅（property/set 与 service/invoke）
  *          本文件与硬件无关，可直接复用。
  ******************************************************************************
  */

#ifndef __ONENET_H
#define __ONENET_H

#include <stdint.h>
#include <stdbool.h>

#include "app_config.h"     /* SEAT_COUNT */

/* 进入透传后建立 MQTT 会话，等待 CONNACK */
bool ONENET_Connect(void);

/* 订阅下行（property/set + service/invoke） */
void ONENET_SubscribeDownlink(void);

/**
 * @brief 属性上行：**一包两个座位**，共 6 个参数
 * @param pressure 每个座位的压力 ADC（0~4095）
 * @param pir      每个座位的红外状态
 * @param alarm    每个座位的假占座告警标志
 * @note  报文形如
 *        {"id":"1","version":"1.0","params":{
 *          "pressure_adc_1":{"value":1200},"pressure_adc_2":{"value":32},
 *          "pir_state_1":{"value":false},"pir_state_2":{"value":false},
 *          "alarm_flag_1":{"value":false},"alarm_flag_2":{"value":true}}}
 *        ⚠️ 这 6 个标识符必须先在中国移动 OneNET 控制台的物模型里建好，
 *           否则平台会整包拒绝（回 code≠200，OLED 记 `PUB ERR xxx`）。
 */
void ONENET_PublishProperty(const uint16_t pressure[SEAT_COUNT],
                            const bool     pir[SEAT_COUNT],
                            const bool     alarm[SEAT_COUNT]);

/**
 * @brief 事件上行：假占座（5 个输出参数，一个都不能少）
 * @param seat_no 云端座位号 1/2（不是内部下标）
 * @note  输出参数：pressure_adc / pir_state / device_id / timestamp / seat_no
 *        少一个平台回 code=2308 event param count error。
 */
void ONENET_PublishFakeOccupy(uint8_t seat_no, uint16_t pressure, bool pir);

/* 事件上行：RFID 刷卡（uid_hex 为 8 位十六进制串，如 "A1B2C3D4"） */
void ONENET_PublishRfid(const char *uid_hex);

/* 心跳保活 */
void ONENET_Ping(void);

/* 处理 ESP 缓冲中的下行报文并分发到 seat_app 回调 */
void ONENET_ProcessIncoming(void);

/**
 * @brief 链路是否被对端（平台）关掉了
 * @note  透传模式下模块断链会往串口吐 `CLOSED` / `WIFI DISCONNECT`，
 *        以前这些字节被当成 MQTT 数据丢弃，设备要等 6 分钟的 pong 超时才重连。
 *        现在识别到就置位，seat_app 立刻回到重连状态（约 5 秒）。
 */
bool ONENET_IsLinkLost(void);
void ONENET_ClearLinkLost(void);

#endif /* __ONENET_H */
