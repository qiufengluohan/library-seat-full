/**
  ******************************************************************************
  * @file    mfrc522.c
  * @brief   MFRC-522 RFID 读卡器驱动（SPI2 + 软件片选 PB12 / 复位 PB1）
  *
  * @note    硬件（以原理图为准）：
  *            SPI2  PB13=SCK / PB14=MISO / PB15=MOSI，软件片选 PB12（模块丝印 SDA）
  *            复位  PB1（模块丝印 RST），IRQ PB9 **不用**（本驱动纯轮询寻卡）
  *
  * @note    2026-10-19 重写（原实现读不到卡，三处硬伤）：
  *          ① **中断位判错**：原来等的是 `irq & 0x01`，而 ComIrqReg 的 bit0 是
  *             **TimerIRq（超时）**，等于"等到超时才算完成"。正确应等
  *             `RxIRq(0x20) | IdleIRq(0x10)`，即 bit0-6 的定义见下。
  *          ② **中断标志没清掉**：ComIrqReg 是「写 0 清除」，原来的
  *             `clear_bit()`（读回值再写）会把已置位的位原样写回去 ⇒ 标志永远挂着，
  *             下一次收发在循环第一拍就"完成"，读到的 FIFO 是空的。
  *             正确做法是一次写 `0x7F`（保留 bit7 Set1）。
  *          ③ **没有 HALT**：读完 UID 不把卡送回 HALT 态，卡会停在 ACTIVE，
  *             之后 REQA 不再应答 ⇒ **同一张卡放上去只能认一次**。
  *          另外 FIFO 冲刷改成直接写 `0x80`（FlushBuffer），不再读回电平位再或。
  *
  * @note    只做「寻卡 + 读 4 字节 UID」，不读扇区、不写卡 —— 签到只需要 UID。
  ******************************************************************************
  */

#include "mfrc522.h"
#include "main.h"
#include "app_config.h"

/* ---------------- 寄存器 ---------------- */
#define REG_COMMAND      0x01U
#define REG_COM_I_EN     0x02U
#define REG_DIV_I_EN     0x03U
#define REG_COM_IRQ      0x04U
#define REG_ERROR        0x06U
#define REG_STATUS2      0x08U
#define REG_FIFO_DATA    0x09U
#define REG_FIFO_LEVEL   0x0AU
#define REG_BIT_FRAMING  0x0DU
#define REG_COLL         0x0EU
#define REG_MODE         0x11U
#define REG_TX_MODE      0x12U
#define REG_RX_MODE      0x13U
#define REG_TX_CONTROL   0x14U
#define REG_TX_ASK       0x15U
#define REG_T_MODE       0x1AU
#define REG_T_PRESCALER  0x1BU
#define REG_T_RELOAD_H   0x1CU
#define REG_T_RELOAD_L   0x1DU
#define REG_VERSION      0x37U

/* ---------------- PCD 命令 ---------------- */
#define PCD_IDLE         0x00U
#define PCD_CALC_CRC     0x03U
#define PCD_TRANSCEIVE   0x0CU
#define PCD_SOFT_RESET   0x0FU

/* ---------------- PICC 命令（ISO14443A） ---------------- */
#define PICC_REQA        0x26U   /* 寻未进入 HALT 的卡 */
#define PICC_HLTA        0x50U   /* 让卡回到 HALT，否则同一张卡不会再应答 REQA */
#define PICC_ANTICOLL    0x93U   /* 防冲突 + 取 UID（cascade level 1） */

/* ---------------- ComIrqReg 位定义（数据手册 §8.1） ----------------
   bit7 Set1 | bit6 TxIRq | bit5 RxIRq | bit4 IdleIRq
   bit3 HiAlertIRq | bit2 LoAlertIRq | bit1 ErrIRq | bit0 TimerIRq */
#define COMIRQ_SET1      0x80U
#define COMIRQ_TX        0x40U
#define COMIRQ_RX        0x20U
#define COMIRQ_IDLE      0x10U
#define COMIRQ_ERR       0x02U
#define COMIRQ_TIMER     0x01U

/* ---------------- ErrorReg 里会导致本帧作废的位 ----------------
   BufferOvfl(0x10) | ParityErr(0x08) | ProtocolErr(0x02) | CollErr(0x01) */
#define ERR_MASK         0x1BU

/** 软件超时：每拍一次 2 字节 SPI。硬件定时器 ~25 ms 也会置 TimerIRq，
 *  两者取先到者，避免片选/接线异常时死等。 */
#define PCD_TIMEOUT_LOOPS 4000U

volatile uint8_t g_mfrc522_version = 0;

/* ============================================================
 * 底层寄存器访问
 * ========================================================== */

static void spi_xfer(const uint8_t *tx, uint8_t *rx, uint16_t len)
{
  HAL_GPIO_WritePin(RFID_SDA_GPIO_Port, RFID_SDA_Pin, GPIO_PIN_RESET);   /* 片选拉低 */
  HAL_SPI_TransmitReceive(&hspi2, (uint8_t *)tx, (uint8_t *)rx, len, 100);
  HAL_GPIO_WritePin(RFID_SDA_GPIO_Port, RFID_SDA_Pin, GPIO_PIN_SET);     /* 片选拉高 */
}

static void write_reg(uint8_t reg, uint8_t val)
{
  uint8_t tx[2] = { (uint8_t)((reg << 1) & 0x7EU), val };
  uint8_t rx[2];
  spi_xfer(tx, rx, 2);
}

static uint8_t read_reg(uint8_t reg)
{
  uint8_t tx[2] = { (uint8_t)(((reg << 1) & 0x7EU) | 0x80U), 0x00 };
  uint8_t rx[2];
  spi_xfer(tx, rx, 2);
  return rx[1];
}

static void set_bit(uint8_t reg, uint8_t mask)
{
  write_reg(reg, (uint8_t)(read_reg(reg) | mask));
}

static void clear_bit(uint8_t reg, uint8_t mask)
{
  write_reg(reg, (uint8_t)(read_reg(reg) & (uint8_t)~mask));
}

/* ============================================================
 * 初始化
 * ========================================================== */

void MFRC522_Init(void)
{
  /* 片选先拉高（不选中），再走一次硬复位 */
  HAL_GPIO_WritePin(RFID_SDA_GPIO_Port, RFID_SDA_Pin, GPIO_PIN_SET);
  HAL_GPIO_WritePin(RFID_RST_GPIO_Port, RFID_RST_Pin, GPIO_PIN_RESET);
  HAL_Delay(10);
  HAL_GPIO_WritePin(RFID_RST_GPIO_Port, RFID_RST_Pin, GPIO_PIN_SET);   /* RST 拉高使能芯片 */
  HAL_Delay(50);

  write_reg(REG_COMMAND, PCD_SOFT_RESET);
  HAL_Delay(50);

  /* 定时器：~25 ms 超时（标准值） */
  write_reg(REG_T_MODE, 0x8D);
  write_reg(REG_T_PRESCALER, 0x3E);
  write_reg(REG_T_RELOAD_H, 0x30);
  write_reg(REG_T_RELOAD_L, 0xE0);

  /* 100% ASK，Mode 0x3D = CRC 预设 0x6363 + 106 kBd */
  write_reg(REG_TX_ASK, 0x40);
  write_reg(REG_MODE, 0x3D);
  write_reg(REG_T_MODE, 0x8D);
  write_reg(REG_RX_MODE, 0x00);
  write_reg(REG_TX_MODE, 0x00);

  /* 打开天线驱动（TxControl 的 Tx1RFEn / Tx2RFEn） */
  set_bit(REG_TX_CONTROL, 0x03);
  HAL_Delay(10);

  /* 读版本号：真片 0x91 / 0x92（克隆片常见 0x12）；0x00 / 0xFF = 接线或片选不对 */
  g_mfrc522_version = read_reg(REG_VERSION);
}

/* ============================================================
 * 收发
 * ========================================================== */

/**
  * @brief 与卡交换一帧
  * @param cmd       PCD_TRANSCEIVE（本驱动只用这个）
  * @param send      待发字节
  * @param send_len  发送长度
  * @param back      接收缓冲，可为 NULL
  * @param back_len  [in]缓冲容量 [out]实际收到的字节数，可为 NULL
  * @return 1 = 收到应答；0 = 超时 / 校验错 / 没有应答
  */
static uint8_t to_card(uint8_t cmd, const uint8_t *send, uint8_t send_len,
                       uint8_t *back, uint8_t *back_len)
{
  uint8_t  irq;
  uint16_t n;

  /* 🔴 清标志：ComIrqReg 是「写 0 清除」，必须直接写 0x7F
     （bit7 Set1 固定为 1，其余全 0 ⇒ 一次性清干净）。 */
  write_reg(REG_COM_I_EN, 0x77);            /* 允许 Rx/Idle/Err/Timer 置位 */
  write_reg(REG_COM_IRQ, 0x7F);             /* ← 关键：不能用 clear_bit() */
  write_reg(REG_FIFO_LEVEL, 0x80);          /* FlushBuffer */
  write_reg(REG_COMMAND, PCD_IDLE);

  for (uint8_t i = 0; i < send_len; i++)
  {
    write_reg(REG_FIFO_DATA, send[i]);
  }

  write_reg(REG_COMMAND, cmd);
  if (cmd == PCD_TRANSCEIVE)
  {
    set_bit(REG_BIT_FRAMING, 0x80);         /* StartSend */
  }

  /* 🔴 等 RxIRq / IdleIRq，不是 bit0（bit0 是 TimerIRq = 超时） */
  n = PCD_TIMEOUT_LOOPS;
  do
  {
    irq = read_reg(REG_COM_IRQ);
    n--;
  } while (((irq & (COMIRQ_RX | COMIRQ_IDLE)) == 0U) &&
           ((irq & COMIRQ_TIMER) == 0U) && (n > 0U));

  clear_bit(REG_BIT_FRAMING, 0x80);         /* 收尾 */

  if ((n == 0U) || ((irq & COMIRQ_TIMER) != 0U))
  {
    return 0;                               /* 硬件定时器超时：多半是没卡 */
  }
  if ((irq & COMIRQ_RX) == 0U)
  {
    return 0;                               /* 没有收到任何字节 */
  }
  if ((read_reg(REG_ERROR) & ERR_MASK) != 0U)
  {
    return 0;                               /* 溢出 / 奇偶 / 协议 / 冲突错 */
  }

  if ((back != NULL) && (back_len != NULL))
  {
    uint8_t len = (uint8_t)(read_reg(REG_FIFO_LEVEL) & 0x7FU);
    uint8_t cap = *back_len;
    if (len > cap)
    {
      len = cap;                            /* 不越界写调用方缓冲 */
    }
    for (uint8_t i = 0; i < len; i++)
    {
      back[i] = read_reg(REG_FIFO_DATA);
    }
    *back_len = len;
  }

  return 1;
}

/** 计算 CRC_A（MFRC522 内置），成功返回 1，结果写 out[0..1] */
static uint8_t crc_a(const uint8_t *data, uint8_t len, uint8_t *out)
{
  uint8_t  irq;
  uint16_t n;

  write_reg(REG_COM_I_EN, 0x77);
  write_reg(REG_COM_IRQ, 0x7F);
  write_reg(REG_FIFO_LEVEL, 0x80);
  write_reg(REG_COMMAND, PCD_IDLE);

  for (uint8_t i = 0; i < len; i++)
  {
    write_reg(REG_FIFO_DATA, data[i]);
  }

  write_reg(REG_COMMAND, PCD_CALC_CRC);

  n = PCD_TIMEOUT_LOOPS;
  do
  {
    irq = read_reg(REG_COM_IRQ);
    n--;
  } while (((irq & COMIRQ_IDLE) == 0U) && (n > 0U));

  if ((n == 0U) || ((read_reg(REG_FIFO_LEVEL) & 0x7FU) != 2U))
  {
    return 0;
  }

  out[0] = read_reg(REG_FIFO_DATA);
  out[1] = read_reg(REG_FIFO_DATA);
  return 1;
}

/** 把卡送回 HALT 态，否则它会停在 ACTIVE，REQA 不再应答 */
static void picc_halt(void)
{
  uint8_t buf[4] = { PICC_HLTA, 0x00U, 0x00U, 0x00U };

  if (crc_a(buf, 2U, &buf[2]) == 0U)
  {
    return;                                 /* CRC 失败就不发了，卡自己离场也会复位 */
  }

  write_reg(REG_BIT_FRAMING, 0x00);
  (void)to_card(PCD_TRANSCEIVE, buf, 4U, NULL, NULL);

  /* HLTA 在协议上不会回 ACK，to_card 返回 0 属正常，不当作错误 */
  write_reg(REG_COM_IRQ, 0x7F);
}

/**
  * @brief 关一次天线再打开：把所有在场卡片复位回 IDLE
  *
  * @note  为什么需要它：如果 REQA 已经让卡进入 READY、但随后的防冲突失败
  *        （误码 / BCC 不对 / 卡中途被拿走），这张卡会**卡在 ACTIVE**，
  *        之后无论怎么刷都不会再应答 REQA —— 现象就是"刷错一次以后再也刷不上，
  *        必须把卡拿开、等一会儿再放"。关一下射频场能立刻把它打回 IDLE。
  *        只在**读了卡但没读成功**时调用，开销 10 ms，不影响正常刷卡。
  */
static void rf_field_reset(void)
{
  clear_bit(REG_TX_CONTROL, 0x03);   /* 关天线 */
  HAL_Delay(5);
  set_bit(REG_TX_CONTROL, 0x03);     /* 重新打开 */
  HAL_Delay(5);
}

/* ============================================================
 * 对外接口
 * ========================================================== */

bool MFRC522_IsCardPresent(void)
{
  uint8_t tag = PICC_REQA;
  uint8_t atqa[2];
  uint8_t al = sizeof(atqa);

  /* 短帧 7 bit：REQA 只有 7 位，BitFraming 的 TxLastBits 必须置 7 */
  write_reg(REG_BIT_FRAMING, 0x07);
  uint8_t ok = to_card(PCD_TRANSCEIVE, &tag, 1U, atqa, &al);
  write_reg(REG_BIT_FRAMING, 0x00);

  return (ok == 1U) && (al == 2U);
}

bool MFRC522_ReadUID(uint8_t *uid, uint8_t *uid_len)
{
  uint8_t send[2] = { PICC_ANTICOLL, 0x20U };   /* 0x93 0x20：CL1 防冲突 */
  uint8_t back[5];
  uint8_t bl = sizeof(back);

  if (uid == NULL)
  {
    return false;
  }

  write_reg(REG_BIT_FRAMING, 0x00);
  if ((to_card(PCD_TRANSCEIVE, send, 2U, back, &bl) != 1U) || (bl != 5U))
  {
    rf_field_reset();     /* 防冲突没成功：把卡打回 IDLE，免得它卡在 ACTIVE 再也不应答 */
    return false;
  }

  /* 第 5 字节是 BCC = UID0^UID1^UID2^UID3，对不上说明是误码 */
  if ((uint8_t)(back[0] ^ back[1] ^ back[2] ^ back[3]) != back[4])
  {
    rf_field_reset();
    return false;
  }

  uid[0] = back[0];
  uid[1] = back[1];
  uid[2] = back[2];
  uid[3] = back[3];
  if (uid_len != NULL)
  {
    *uid_len = 4U;
  }

  picc_halt();      /* 🔴 关键：不 HALT 的话，同一张卡放上去只能被认一次 */
  return true;
}
