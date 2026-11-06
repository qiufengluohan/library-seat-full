/**
  ******************************************************************************
  * @file    mfrc522.h
  * @brief   MFRC-522 RFID 读卡器驱动（SPI，ISO14443A）
  *
 * @note    硬件：SPI2（PB13=SCK / PB14=MISO / PB15=MOSI）
 *          软件片选 PB12（模块丝印为 SDA，实际就是 SPI 的 NSS）
 *          复位 PB1（模块丝印 RST）
 *          IRQ PB9 本驱动**不用**（走轮询寻卡），保持输入即可。
 *
 * @note    2026-10-19 重写实现（接口不变）：修 ComIrqReg 判位/清零错误、
 *          FIFO 冲刷写法、并在读完 UID 后补 HALT（否则同一张卡只认一次）。
 *          版本寄存器值见 g_mfrc522_version，真片 0x91/0x92。
  ******************************************************************************
  */

#ifndef __MFRC522_H
#define __MFRC522_H

#include <stdint.h>
#include <stdbool.h>

/** 供 IDE 观察：芯片版本寄存器值。真 MFRC522 通常为 0x91 或 0x92；
 *  若读到 0x00 或 0xFF，基本可判定 SPI 接线/片选有问题。 */
extern volatile uint8_t g_mfrc522_version;

void MFRC522_Init(void);
bool MFRC522_IsCardPresent(void);
bool MFRC522_ReadUID(uint8_t *uid, uint8_t *uid_len);   /* uid 至少 4 字节 */

#endif /* __MFRC522_H */
