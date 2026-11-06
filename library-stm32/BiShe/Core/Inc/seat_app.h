/**
  ******************************************************************************
  * @file    seat_app.h
  * @brief   座位终端业务编排：连接状态机 + 采集/融合/RFID 轮询 + 下行回调
  ******************************************************************************
  */

#ifndef __SEAT_APP_H
#define __SEAT_APP_H

#include <stdint.h>
#include <stdbool.h>

void SEAT_App_Init(void);
void SEAT_App_Process(void);     /* 主循环里反复调用 */

/* 下行回调（由 onenet.c 调用） */
void SEAT_OnPropertySet(const char *json);
void SEAT_OnServiceInvoke(const char *service_id, const char *json);
void SEAT_OnPingResp(void);

#endif /* __SEAT_APP_H */
