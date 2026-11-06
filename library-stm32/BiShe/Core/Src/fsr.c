/**
  ******************************************************************************
  * @file    fsr.c
  * @brief   FSR402 压力采集（轮询 + 滑动平均 + 标定辅助）
  *
  * 硬件：FSR402 与 10.1kΩ 下拉电阻构成分压，输出接 ADC 输入脚
  *       FSR1 -> PA0 (ADC1_IN0)，FSR2 -> PA1 (ADC2_IN1)
  *
 * 路数由 app_config.h 的 **FSR_ACTIVE_MASK** 决定（按位选通）：
 *       bit0 = PA0（原理图 FSR1 座子），bit1 = PA1（原理图 FSR402 座子）
 *       当前 0x02 = 只用 PA1（本板 PA0 那一路开路）
 *       ⚠️ 选通只影响判定；两路都会被采样，OLED 上都能看到。
 *
 * ⚠️ 本文件现在**无条件**引用 hadc2（CH2 必须真的走 PA1/ADC2_IN1）。
 *    所以 `MX_ADC2_Init()` 必须保留在 main.c 里；若哪天要在 CubeMX 里删掉 ADC2，
 *    得同时把 fsr_handle() 改回只返回 hadc1。
 *
 * 采样方式：软件触发单次转换（CubeMX 里 ScanConvMode / ContinuousConvMode 均为 DISABLE）
  ******************************************************************************
  */

#include "fsr.h"
#include "adc.h"
#include "app_config.h"

#if (FSR_ACTIVE_MASK == 0U) || (FSR_ACTIVE_MASK > ((1U << FSR_CH_NUM) - 1U))
#error "app_config.h 里的 FSR_ACTIVE_MASK 只能是 0x01 / 0x02 / 0x03"
#endif

/* ----------------------------- 内部状态 ----------------------------- */

/** 每路一个滑动平均缓冲区（环形） */
static uint16_t s_buf[FSR_CH_NUM][FSR_FILTER_LEN];
static uint8_t  s_idx[FSR_CH_NUM];      /* 下一个写入位置 */
static uint8_t  s_filled[FSR_CH_NUM];   /* 已填充个数，未满时用已有样本平均 */
static uint32_t s_sum[FSR_CH_NUM];      /* 窗口内累加和，避免每次重算 */

/** 最近一次成功采样的原始值 */
static uint16_t s_last_raw[FSR_CH_NUM];

/** 对外可观察变量 */
volatile uint16_t g_fsr_raw[FSR_CH_NUM];
volatile uint16_t g_fsr_filtered[FSR_CH_NUM];
volatile uint16_t g_fsr_min[FSR_CH_NUM];
volatile uint16_t g_fsr_max[FSR_CH_NUM];
volatile uint32_t g_fsr_err_cnt[FSR_CH_NUM];
/** 上电空载基线：FSR_Init() 末尾采到的滤波值（用作相对阈值的零点） */
volatile uint16_t g_fsr_zero[FSR_CH_NUM];

/* ----------------------------- 内部函数 ----------------------------- */

/**
 * @brief 取通道对应的 ADC 句柄
 */
static ADC_HandleTypeDef *fsr_handle(uint8_t ch)
{
    /* CH2 必须走 hadc2（PA1 / ADC2_IN1）。
       旧实现在 FSR_CH_USED==1 时把任何通道都返回 hadc1 ——
       于是 OLED 上的 "P1" 其实是「再采一次 PA0」，两列永远一样，
       排查"FSR 到底插在 PA0 还是 PA1"时会被彻底误导。现在改为真实映射。 */
    return (ch == FSR_CH2) ? &hadc2 : &hadc1;
}

/**
 * @brief 轮询方式读一次 ADC
 * @retval 0..4095 转换结果；0xFFFF 表示失败（超时或启动失败）
 */
static uint16_t fsr_read_once(uint8_t ch)
{
    ADC_HandleTypeDef *hadc = fsr_handle(ch);

    if (HAL_ADC_Start(hadc) != HAL_OK)
    {
        return 0xFFFFU;
    }
    if (HAL_ADC_PollForConversion(hadc, FSR_POLL_TIMEOUT_MS) != HAL_OK)
    {
        (void)HAL_ADC_Stop(hadc);          /* 超时也要释放，避免卡住下一次 */
        return 0xFFFFU;
    }

    uint16_t value = (uint16_t)HAL_ADC_GetValue(hadc);
    (void)HAL_ADC_Stop(hadc);
    return value;
}

/**
 * @brief 把新样本压入滑动平均窗口，返回当前平均值
 */
static uint16_t fsr_filter_push(uint8_t ch, uint16_t sample)
{
    /* 窗口未满时不出队，满时减去被覆盖的旧样本 */
    if (s_filled[ch] == FSR_FILTER_LEN)
    {
        s_sum[ch] -= s_buf[ch][s_idx[ch]];
    }
    else
    {
        s_filled[ch]++;
    }

    s_buf[ch][s_idx[ch]] = sample;
    s_sum[ch] += sample;
    s_idx[ch] = (uint8_t)((s_idx[ch] + 1U) % FSR_FILTER_LEN);

    return (uint16_t)(s_sum[ch] / s_filled[ch]);
}

/* ------------------------------- 接口 ------------------------------- */

void FSR_Init(void)
{
    uint8_t ch;

    /* F1 系列 ADC 首次转换前必须自校准，不校准会导致读数明显偏差。
       ADC2 也要校准：即使只用一路，OLED 的 P1 列现在是真的在采 PA1。 */
    (void)HAL_ADCEx_Calibration_Start(&hadc1);
    (void)HAL_ADCEx_Calibration_Start(&hadc2);

    for (ch = 0U; ch < FSR_CH_NUM; ch++)
    {
        uint8_t i;
        for (i = 0U; i < FSR_FILTER_LEN; i++)
        {
            s_buf[ch][i] = 0U;
        }
        s_idx[ch]      = 0U;
        s_filled[ch]   = 0U;
        s_sum[ch]      = 0U;
        s_last_raw[ch] = 0U;

        g_fsr_raw[ch]      = 0U;
        g_fsr_filtered[ch] = 0U;
        g_fsr_min[ch]      = 0xFFFFU;
        g_fsr_max[ch]      = 0U;
        g_fsr_err_cnt[ch]  = 0U;
        g_fsr_zero[ch]     = 0U;
    }

    /* 填满两路滤波窗口 + 采集空载基线（实现见 FSR_Rezero）
       ① 避免刚上电时平均值的爬升过程被误判成压力变化；
       ② 让 OLED 的 P0/P1 与 FSR 调试页从第一帧起就有真实数据。 */
    FSR_Rezero();

    /* 读一次内部基准电压 VREFINT：正常约 1480 ⇒ ADC 正常；
       OLED 第 7 行会显示 "V:xxxx"，用来把"ADC 坏"和"外部没接好"区分开。 */
    g_fsr_vref = FSR_ReadVrefint();
}

void FSR_RezeroCh(uint8_t ch)
{
    uint8_t i;

    if (ch >= FSR_CH_NUM)
    {
        return;
    }

    /* 丢弃旧窗口，避免上一次的样本把新基线拖偏 */
    for (i = 0U; i < FSR_FILTER_LEN; i++)
    {
        s_buf[ch][i] = 0U;
    }
    s_idx[ch]    = 0U;
    s_filled[ch] = 0U;
    s_sum[ch]    = 0U;

    /* 重新采满一窗，最后一步的滤波值就是"当前空载值" */
    for (i = 0U; i < FSR_FILTER_LEN; i++)
    {
        uint16_t v = fsr_read_once(ch);
        if (v == 0xFFFFU)
        {
            g_fsr_err_cnt[ch]++;
            break;
        }
        s_last_raw[ch]     = v;
        g_fsr_filtered[ch] = fsr_filter_push(ch, v);
    }

    g_fsr_raw[ch]  = s_last_raw[ch];
    g_fsr_zero[ch] = g_fsr_filtered[ch];   /* 新的零点 */
    g_fsr_min[ch]  = g_fsr_filtered[ch];   /* 极值统计从零点重新起算 */
    g_fsr_max[ch]  = g_fsr_filtered[ch];
}

void FSR_Rezero(void)
{
    uint8_t ch;

    for (ch = 0U; ch < FSR_CH_NUM; ch++)
    {
        FSR_RezeroCh(ch);
    }
}

uint16_t FSR_ReadRaw(uint8_t ch)
{
    uint16_t v;

    /* 说明：这里按 FSR_CH_NUM 判断，**两路总能读**（OLED 会同时显示 P0/P1，
       用来判断 FSR 实际接在 PA0 还是 PA1）。
       V17 双座位：座位 i 固定用通道 i，判定走 SENSORS_IsPressedSeat(i)；
       "读取另一路做显示"不会影响"是否受压"的判定。 */
    if (ch >= FSR_CH_NUM)
    {
        return 0U;
    }

    v = fsr_read_once(ch);
    if (v == 0xFFFFU)
    {
        /* 采样失败：累计错误计数并沿用上次值，保证上层拿到的是"可信的旧值"而不是 0 */
        g_fsr_err_cnt[ch]++;
        return s_last_raw[ch];
    }

    s_last_raw[ch] = v;
    g_fsr_raw[ch]  = v;
    return v;
}

uint16_t FSR_ReadFiltered(uint8_t ch)
{
    uint16_t raw;

    /* 注意：这里按 FSR_CH_NUM 判断，**两路都能滤波**。
       "哪几路参与有人/无人判定"由 SENSORS_IsPressedSeat() 按座位各自决定。
       这样即使只接了一路，OLED 的 P0/P1/F0/F1/M0/M1 仍然是真实读数，能用来查接线。 */
    if (ch >= FSR_CH_NUM)
    {
        return 0U;
    }

    raw = FSR_ReadRaw(ch);
    g_fsr_filtered[ch] = fsr_filter_push(ch, raw);

    /* 标定统计：把"上电以来的最小/最大值"直接在这里维护。
       原来只在 FSR_Poll() 里更新，可 FSR_Poll() 从来没被调用过，
       ⇒ g_fsr_min/max 永远停在 0xFFFF / 0，标定页等于是假的。 */
    if (g_fsr_filtered[ch] < g_fsr_min[ch])
    {
        g_fsr_min[ch] = g_fsr_filtered[ch];
    }
    if (g_fsr_filtered[ch] > g_fsr_max[ch])
    {
        g_fsr_max[ch] = g_fsr_filtered[ch];
    }

    return g_fsr_filtered[ch];
}

uint16_t FSR_GetZero(uint8_t ch)
{
    return (ch < FSR_CH_NUM) ? g_fsr_zero[ch] : 0U;
}

uint16_t FSR_GetLast(uint8_t ch)
{
    return (ch < FSR_CH_NUM) ? g_fsr_filtered[ch] : 0U;
}

uint16_t FSR_GetMv(uint8_t ch)
{
    if (ch >= FSR_CH_NUM)
    {
        return 0U;
    }
    /* 先乘后除，尽量保留精度；4095*3300 = 13,513,500 未超 uint32 */
    return (uint16_t)(((uint32_t)g_fsr_raw[ch] * FSR_VDDA_MV) / FSR_ADC_FULLSCALE);
}

void FSR_Poll(void)
{
    uint8_t ch;

    for (ch = 0U; ch < FSR_CH_NUM; ch++)
    {
        uint16_t filtered;

        if (!FSR_CH_ACTIVE(ch))
        {
            continue;
        }

        filtered = FSR_ReadFiltered(ch);

        /* 标定辅助：记录观测区间，便于确定"有人/无人/放物"三档阈值 */
        if (filtered < g_fsr_min[ch])
        {
            g_fsr_min[ch] = filtered;
        }
        if (filtered > g_fsr_max[ch])
        {
            g_fsr_max[ch] = filtered;
        }
    }
}

void FSR_DebugReset(void)
{
    uint8_t ch;
    for (ch = 0U; ch < FSR_CH_NUM; ch++)
    {
        g_fsr_min[ch] = 0xFFFFU;
        g_fsr_max[ch] = 0U;
    }
}

uint8_t FSR_IsPressed(uint8_t ch, uint16_t threshold)
{
    if ((ch >= FSR_CH_NUM) || !FSR_CH_ACTIVE(ch))
    {
        return 0U;
    }
    return (g_fsr_filtered[ch] >= threshold) ? 1U : 0U;
}

/* ============ 内部基准 VREFINT 自检：判断 ADC 本身是否正常 ============ */

volatile uint16_t g_fsr_vref = 0U;

uint16_t FSR_ReadVrefint(void)
{
    ADC_ChannelConfTypeDef cfg = {0};
    uint16_t v = 0U;

    /* VREFINT（通道 17）需要先把 CR2 的 TSVREFE 置位才使能 */
    SET_BIT(hadc1.Instance->CR2, ADC_CR2_TSVREFE);

    cfg.Channel      = ADC_CHANNEL_VREFINT;
    cfg.Rank         = 1U;
    cfg.SamplingTime = ADC_SAMPLETIME_239CYCLES_5;   /* VREFINT 要求较长采样 */

    if (HAL_ADC_ConfigChannel(&hadc1, &cfg) == HAL_OK)
    {
        if (HAL_ADC_Start(&hadc1) == HAL_OK)
        {
            if (HAL_ADC_PollForConversion(&hadc1, FSR_POLL_TIMEOUT_MS) == HAL_OK)
            {
                v = (uint16_t)HAL_ADC_GetValue(&hadc1);
            }
            (void)HAL_ADC_Stop(&hadc1);
        }
    }

    /* 恢复成 PA0（CHANNEL_0），别把后续采样搞坏 */
    cfg.Channel      = ADC_CHANNEL_0;
    cfg.SamplingTime = ADC_SAMPLETIME_239CYCLES_5;
    (void)HAL_ADC_ConfigChannel(&hadc1, &cfg);
    CLEAR_BIT(hadc1.Instance->CR2, ADC_CR2_TSVREFE);

    return v;
}
