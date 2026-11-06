/**
  ******************************************************************************
  * @file    oled_ssd1306.c
  * @brief   SSD1306 I2C 驱动实现（128x64，水平寻址）
  *
  * @note    硬件：I2C1（PB6/PB7），本文件内的引脚/句柄引用与旧工程一致，
  *          属于可直接复用的模块。
  *
  *          ⚠️ 关于刷新耗时：整屏 1024 字节 + 控制字节，在 I2C 100kHz 下
  *             OLED_Refresh() 约阻塞 90~100ms。因此不要在主循环里每圈都刷新，
  *             只在状态变化时调用一次。
  *             若嫌慢：把 CubeMX 里 I2C1 的 Speed Mode 从 Standard(100kHz)
  *             改成 Fast Mode(400kHz)，刷新时间可降到约 25ms（SSD1306 支持 400kHz）。
  ******************************************************************************
  */

#include "oled_ssd1306.h"
#include "main.h"
#include "app_config.h"
#include <string.h>

#define OLED_W  128
#define OLED_H  64
#define OLED_PAGES (OLED_H / 8)

/* I2C 传输超时（ms）。模块不在线时会等满这个时间，所以别设太大 */
#define OLED_I2C_TIMEOUT  50

static uint8_t oled_buf[OLED_W * OLED_PAGES];

/** 供 IDE 观察：I2C 写失败次数（OLED 没插好/接线松时会一直涨） */
volatile uint32_t g_oled_i2c_err = 0;

/** 运行期实际使用的 7 位 I2C 地址（初始化时自动探测 0x3C~0x3F）+ 是否找到设备 */
volatile uint8_t g_oled_i2c_addr = OLED_I2C_ADDR;
volatile uint8_t g_oled_found = 0;

/* ---------- 5x7 ASCII 字体（0x20..0x5F），列主序：每字符 5 字节=5 列，
   每字节 bit0..bit6 对应行 0..6（bit0=顶行）。 ---------- */
static const uint8_t font[0x40][5] = {
  {0,0,0,0,0},            /* 0x20 */
  {0,0,0x7D,0,0},         /* 0x21 ! */
  {0,0,0,0,0}, {0,0,0,0,0}, {0,0,0,0,0}, {0,0,0,0,0}, {0,0,0,0,0},
  {0,0,0,0,0}, {0,0,0,0,0}, {0,0,0,0,0}, {0,0,0,0,0}, {0,0,0,0,0},
  /* ⚠️ 2026-10-18 补上漏掉的 ',' (0x2C) 字形。
     原表只有 63 个元素（数组声明 64），从 0x2C 起每个字符都会取到"下一个字符"的字形，
     现象：屏幕上 SEAT_001 显示成 TFRU 1i2、ESP OK 115200 显示成 FTQ PL 226311。
     （这个缺陷是从旧工程原样搬过来的，旧工程的屏也有同样问题。） */
  {0,0,0,0x40,0x60},      /* 0x2C , */
  {0x08,0x08,0x08,0x08,0x08}, /* 0x2D - */
  {0,0,0,0,0x60},         /* 0x2E . */
  {0x60,0x10,0x08,0x04,0x03}, /* 0x2F / */
  {0x3E,0x51,0x49,0x45,0x3B}, /* 0x30 0 */
  {0x00,0x42,0x7E,0x40,0x00}, /* 0x31 1 */
  {0x03,0x31,0x29,0x25,0x3B}, /* 0x32 2 */
  {0x41,0x49,0x49,0x49,0x36}, /* 0x33 3 */
  {0x0F,0x08,0x08,0x7F,0x0F}, /* 0x34 4 */
  {0x33,0x49,0x49,0x39,0x79}, /* 0x35 5 */
  {0x3E,0x49,0x49,0x79,0x70}, /* 0x36 6 */
  {0x01,0x71,0x09,0x05,0x03}, /* 0x37 7 */
  {0x36,0x49,0x49,0x49,0x36}, /* 0x38 8 */
  {0x0E,0x49,0x49,0x49,0x7E}, /* 0x39 9 */
  {0,0,0x36,0,0},           /* 0x3A : */
  {0,0,0,0,0}, {0,0,0,0,0}, {0,0,0,0,0}, {0,0,0,0,0}, {0,0,0,0,0},
  {0,0,0,0,0},             /* 0x40 @ */
  {0x7E,0x09,0x08,0x09,0x7E}, /* 0x41 A */
  {0x7F,0x09,0x09,0x09,0x36}, /* 0x42 B */
  {0x3E,0x41,0x41,0x41,0x41}, /* 0x43 C */
  {0x7F,0x41,0x41,0x41,0x3E}, /* 0x44 D */
  {0x7F,0x49,0x49,0x49,0x41}, /* 0x45 E */
  {0x7F,0x09,0x09,0x09,0x01}, /* 0x46 F */
  {0x3E,0x41,0x41,0x09,0x79}, /* 0x47 G */
  {0x7F,0x08,0x08,0x08,0x7F}, /* 0x48 H */
  {0x41,0x41,0x7F,0x41,0x41}, /* 0x49 I */
  {0,0,0,0,0},             /* 0x4A J */
  {0x7F,0x08,0x14,0x22,0x41}, /* 0x4B K */
  {0x7F,0x40,0x40,0x40,0x40}, /* 0x4C L */
  {0x7F,0x02,0x0C,0x02,0x7F}, /* 0x4D M */
  {0x7F,0x02,0x0C,0x10,0x7F}, /* 0x4E N */
  {0x3E,0x41,0x41,0x41,0x3E}, /* 0x4F O */
  {0x7F,0x09,0x09,0x09,0x06}, /* 0x50 P */
  {0x3E,0x41,0x51,0x21,0x3B}, /* 0x51 Q */
  {0x7F,0x09,0x1D,0x19,0x46}, /* 0x52 R */
  {0x4E,0x09,0x09,0x49,0x79}, /* 0x53 S */
  {0x01,0x01,0x7F,0x01,0x01}, /* 0x54 T */
  {0x3F,0x40,0x40,0x40,0x7F}, /* 0x55 U */
  {0x1F,0x1F,0x7F,0x1F,0x1F}, /* 0x56 V */
  {0x7F,0x3F,0x1F,0x3F,0x7F}, /* 0x57 W */
  {0,0,0,0,0},             /* 0x58 X */
  {0x03,0x07,0x7F,0x03,0x03}, /* 0x59 Y */
  {0x61,0x51,0x49,0x45,0x43}, /* 0x5A Z */
  {0,0,0,0,0}, {0,0,0,0,0}, {0,0,0,0,0},
  {0x40,0x40,0x40,0x40,0x40}  /* 0x5F _ */
};

/* 编译期自检：字形表必须**正好** 0x40 个元素（对应 0x20..0x5F），
   少一个就会导致 0x2C 之后所有字符整体错位成"下一个字符"（这个坑踩过一次）。
   一旦以后有人删/加了一行，编译直接报错。 */
_Static_assert((sizeof(font) / sizeof(font[0])) == 0x40,
               "font table must have exactly 0x40 (64) glyphs, otherwise characters shift by one");

static void oled_cmd(uint8_t c)
{
  uint8_t buf[2] = {0x00, c};   /* 0x00 = 命令控制字节 */
  if (HAL_I2C_Master_Transmit(&hi2c1, (uint16_t)(g_oled_i2c_addr << 1),
                              buf, 2, OLED_I2C_TIMEOUT) != HAL_OK)
  {
    g_oled_i2c_err++;
  }
}

static void oled_data(const uint8_t *data, uint16_t len)
{
  uint16_t off = 0;
  uint8_t buf[OLED_W + 1];
  buf[0] = 0x40;  /* 0x40 = 数据控制字节 */
  while (off < len)
  {
    uint16_t n = (len - off > OLED_W) ? OLED_W : (len - off);
    memcpy(buf + 1, data + off, n);
    if (HAL_I2C_Master_Transmit(&hi2c1, (uint16_t)(g_oled_i2c_addr << 1),
                                buf, (uint16_t)(n + 1), OLED_I2C_TIMEOUT * 2) != HAL_OK)
    {
      g_oled_i2c_err++;
    }
    off += n;
  }
}

void OLED_Init(void)
{
  /* ---- I2C 地址自动探测 ----
     常见的 SSD1306 模块 7 位地址有 **0x3C 和 0x3D** 两种（少数 0x3E/0x3F）。
     以前代码把 0x3C 写死，一旦买到 0x3D 的模块，所有写操作都 NACK，
     表现就是"屏幕一直显示旧内容/不动，但程序在跑"。
     这里用 HAL_I2C_IsDeviceReady 逐个探测（读 ACK），找到哪个就用哪个。 */
  static const uint8_t cand[] = { 0x3C, 0x3D, 0x3E, 0x3F };
  uint8_t k;
  uint8_t round;

  g_oled_found = 0U;

  /* 🔴 探测必须重试：冷上电时模块内部的上电复位/电荷泵还没到位，第一次问很可能 NACK。
     以前"一次不应答就整场黑屏"，只能靠重新烧录救回来（烧录只复位 MCU，不断屏的电）。 */
  for (round = 0U; (round < OLED_PROBE_RETRY) && (g_oled_found == 0U); round++)
  {
    for (k = 0U; k < (uint8_t)sizeof(cand); k++)
    {
      if (HAL_I2C_IsDeviceReady(&hi2c1, (uint16_t)(cand[k] << 1), 3U, 50U) == HAL_OK)
      {
        g_oled_i2c_addr = cand[k];
        g_oled_found    = 1U;
        break;
      }
    }
    if ((g_oled_found == 0U) && ((round + 1U) < OLED_PROBE_RETRY))
    {
      HAL_Delay(OLED_PROBE_RETRY_MS);
    }
  }
  if (g_oled_found == 0U)
  {
    /* 一个都没应答：仍按默认地址继续试写（便于用 g_oled_i2c_err 观察错误数），
       同时 g_oled_found=0 会让 seat_app 用蜂鸣器报警 */
    g_oled_i2c_addr = OLED_I2C_ADDR;
  }

  /* 初始化序列**分两段**发：0x8D,0x14 打开电荷泵之后必须留时间升压，
     否则紧接着发的 0xAF 就算写进去了，面板也不会亮。
     以前整条数组一口气发完、中间没有任何延时 —— 冷上电黑屏的第二个原因。 */
  static const uint8_t seq_pre[] = {
    0xAE,             /* 关显示 */
    0xD5, 0x80,       /* 显示时钟分频 */
    0xA8, 0x3F,       /* 多路复用 63 */
    0xD3, 0x00,       /* 显示偏移 0 */
    0x40,             /* 起始行 0 */
    0x8D, 0x14        /* 电荷泵开启（开完要等它把 VCC 抬到 ~7.5V） */
  };
  static const uint8_t seq_post[] = {
    0x20, 0x00,       /* 水平寻址模式 */
    0xA1,             /* 段重映射 */
    0xC8,             /* COM 扫描方向 */
    0xDA, 0x12,       /* COM 引脚配置 */
    0x81, 0xCF,       /* 对比度 */
    0xD9, 0xF1,       /* 预充电 */
    0xDB, 0x40,       /* VCOMH */
    0x2E,             /* 关闭滚动 */
    0xA4,             /* 映射到 RAM */
    0xA6,             /* 正常显示 */
    0xAF              /* 开显示 */
  };

  for (uint8_t i = 0; i < (uint8_t)sizeof(seq_pre); i++)
  {
    oled_cmd(seq_pre[i]);
  }
  HAL_Delay(OLED_CHARGE_PUMP_DELAY_MS);
  for (uint8_t i = 0; i < (uint8_t)sizeof(seq_post); i++)
  {
    oled_cmd(seq_post[i]);
  }

  OLED_Clear();
  OLED_Refresh();
}

void OLED_Clear(void)
{
  memset(oled_buf, 0, sizeof(oled_buf));
}

void OLED_Fill(uint8_t pattern)
{
  memset(oled_buf, pattern, sizeof(oled_buf));
}

void OLED_SetPagePattern(uint8_t page, uint8_t pattern)
{
  if (page >= (uint8_t)OLED_PAGES)
  {
    return;
  }
  memset(&oled_buf[(uint16_t)page * OLED_W], pattern, OLED_W);
}

void OLED_Refresh(void)
{
  oled_cmd(0x21); oled_cmd(0x00); oled_cmd(0x7F);  /* 列地址 0..127 */
  oled_cmd(0x22); oled_cmd(0x00); oled_cmd(0x07);  /* 页地址 0..7 */
  oled_data(oled_buf, sizeof(oled_buf));
}

static void oled_set_px(uint8_t x, uint8_t y, bool on)
{
  if (x >= OLED_W || y >= OLED_H) return;
  uint16_t idx = (uint16_t)((y / 8) * OLED_W + x);
  uint8_t bit = (uint8_t)(y % 8);
  if (on) oled_buf[idx] |= (uint8_t)(1u << bit);
  else    oled_buf[idx] &= (uint8_t)~(1u << bit);
}

void OLED_DrawString(uint8_t x, uint8_t y, const char *s)
{
  if (s == NULL) return;
  uint8_t cx = x;
  for (; *s && cx < OLED_W - 6; s++)
  {
    char c = *s;
    if (c >= 'a' && c <= 'z') c = (char)(c - 32);     /* 小写转大写 */
    uint8_t code = (uint8_t)c;
    const uint8_t *glyph = NULL;
    if (code >= 0x20 && code <= 0x5F) glyph = font[code - 0x20];
    for (uint8_t col = 0; col < 5; col++)
    {
      uint8_t rowbits = glyph ? glyph[col] : 0;
      for (uint8_t r = 0; r < 7; r++)
      {
        if (rowbits & (1u << r))
          oled_set_px((uint8_t)(cx + col), (uint8_t)(y * 8 + r), true);
      }
    }
    cx += 6;   /* 5 宽 + 1 间隔 */
  }
}

void OLED_ShowStatus(uint8_t display_code, bool alarm)
{
  const char *line2;
  switch (display_code)
  {
    case 1:  line2 = "RESV"; break;   /* 已预约/待签到 */
    case 2:  line2 = "USED"; break;   /* 使用中 */
    case 3:  line2 = "AWAY"; break;   /* 暂离 */
    case 4:  line2 = "ALRM"; break;   /* 告警（云端下发） */
    default: line2 = "IDLE"; break;   /* 空闲 */
  }
  if (alarm) line2 = "ALRM";          /* 本地假占座优先显示告警 */

  OLED_Clear();
  OLED_DrawString(0, 0, DEVICE_NAME);   /* 第一行：设备名 */
  OLED_DrawString(0, 1, line2);         /* 第二行：状态 */
  OLED_Refresh();
}
