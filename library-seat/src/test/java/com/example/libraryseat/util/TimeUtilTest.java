package com.example.libraryseat.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 时间工具测试。
 *
 * <p>四个坑都出在"看不见的地方"：
 * <ol>
 *   <li>设备上报的 timestamp 是秒还是毫秒<b>没有约定</b>（规范 §15 写秒，
 *       Studio 实测可能是毫秒），判错就是 1970 年或公元 5138 年，
 *       而 last_report_at 一错，离线巡检会把所有设备判成离线。</li>
 *   <li>时区。{@code startOfToday} 必须在 Asia/Shanghai 算，
 *       否则 Dashboard 的"今日"和 MySQL 的 CURDATE() 差 8 小时。</li>
 *   <li>学习时长。不足一分钟按 0 算、时钟回拨不写负数 ——
 *       这两条都是防刷时长的，写在 study_record 里就再也改不回来了。</li>
 *   <li>小数秒。MySQL 存进 {@code DATETIME(0)} 时<b>四舍五入</b>，
 *       REST 格式化<b>截断</b>，两边会差 1 秒，见
 *       {@link #fractionalSecondPutsRestAndMysqlInConflict()}。</li>
 * </ol>
 */
class TimeUtilTest {

    /** 2025-06-15T15:06:40Z，也就是上海时间 2025-06-15 23:06:40。 */
    private static final long EPOCH_SECONDS = 1750000000L;
    private static final LocalDateTime SHANGHAI_TIME = LocalDateTime.of(2025, 6, 15, 23, 6, 40);

    @Test
    @DisplayName("fromEpoch：秒")
    void fromEpochSeconds() {
        assertEquals(SHANGHAI_TIME, TimeUtil.fromEpoch(EPOCH_SECONDS));
    }

    @Test
    @DisplayName("fromEpoch：毫秒，与同一时刻的秒值结果一致")
    void fromEpochMillis() {
        assertEquals(SHANGHAI_TIME, TimeUtil.fromEpoch(EPOCH_SECONDS * 1000));
        assertEquals(TimeUtil.fromEpoch(EPOCH_SECONDS), TimeUtil.fromEpoch(EPOCH_SECONDS * 1000));
    }

    @Test
    @DisplayName("fromEpoch：null / 0 / 负数都返回 null，让调用方退回服务器当前时间")
    void fromEpochRejectsNonPositive() {
        assertNull(TimeUtil.fromEpoch(null));
        assertNull(TimeUtil.fromEpoch(0L));
        assertNull(TimeUtil.fromEpoch(-1L));
    }

    @Test
    @DisplayName("toEpochSecond 与 fromEpoch 互为逆运算")
    void epochRoundTrip() {
        assertEquals(EPOCH_SECONDS, TimeUtil.toEpochSecond(TimeUtil.fromEpoch(EPOCH_SECONDS)));
        assertEquals(0L, TimeUtil.toEpochSecond(null));
    }

    @Test
    @DisplayName("小数秒会让 REST 与 DATETIME(0) 分家：截断 vs 四舍五入")
    void fractionalSecondPutsRestAndMysqlInConflict() {
        LocalDateTime fractional = SHANGHAI_TIME.withNano(600_000_000);
        // REST 侧截断成 :40；MySQL 存进 DATETIME(0) 时四舍五入成 :41。
        // 强制释放就是踩在这条缝上：reserve_time 回读（:41）、release_time 用内存值（:40），
        // 于是接口返回"释放比预约早 1 秒"，管理端算出负时长。
        String restSide = TimeUtil.format(fractional);
        String mysqlSide = TimeUtil.format(fractional.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
        assertEquals("2025-06-15 23:06:40", restSide);
        assertNotEquals(restSide, mysqlSide);

        // 所以两个入口必须只产出整秒，冲突才不会回来。
        assertEquals(0, TimeUtil.now().getNano());
        assertEquals(0, TimeUtil.fromEpoch(EPOCH_SECONDS * 1000 + 999).getNano());
        assertEquals(0, TimeUtil.startOfToday().getNano());
    }

    @Test
    @DisplayName("fromText：epoch 数字串、ISO-8601、REST 格式三种都认")
    void fromTextAcceptsThreeShapes() {
        assertEquals(SHANGHAI_TIME, TimeUtil.fromText("1750000000"));
        assertEquals(SHANGHAI_TIME, TimeUtil.fromText("2025-06-15T15:06:40Z"));
        assertEquals(SHANGHAI_TIME, TimeUtil.fromText("2025-06-15 23:06:40"));
    }

    @Test
    @DisplayName("fromText：解析不出来返回 null，不抛异常")
    void fromTextReturnsNullForGarbage() {
        assertNull(TimeUtil.fromText(null));
        assertNull(TimeUtil.fromText(""));
        assertNull(TimeUtil.fromText("昨天下午"));
    }

    @Test
    @DisplayName("minutesBetween：不足一分钟按 0 算，不四舍五入")
    void minutesBetweenTruncates() {
        LocalDateTime start = SHANGHAI_TIME;
        assertEquals(0, TimeUtil.minutesBetween(start, start.plusSeconds(59)));
        assertEquals(1, TimeUtil.minutesBetween(start, start.plusSeconds(60)));
        assertEquals(1, TimeUtil.minutesBetween(start, start.plusSeconds(119)));
        assertEquals(90, TimeUtil.minutesBetween(start, start.plusMinutes(90)));
    }

    @Test
    @DisplayName("minutesBetween：null 或时钟回拨都返回 0，不写负数进 study_record")
    void minutesBetweenNeverNegative() {
        assertEquals(0, TimeUtil.minutesBetween(null, SHANGHAI_TIME));
        assertEquals(0, TimeUtil.minutesBetween(SHANGHAI_TIME, null));
        assertEquals(0, TimeUtil.minutesBetween(SHANGHAI_TIME, SHANGHAI_TIME.minusMinutes(30)));
    }

    @Test
    @DisplayName("startOfToday 是今天零点，且不晚于当前时间")
    void startOfTodayIsMidnight() {
        LocalDateTime today = TimeUtil.startOfToday();
        LocalDateTime now = TimeUtil.now();

        assertEquals(0, today.getHour());
        assertEquals(0, today.getMinute());
        assertEquals(0, today.getSecond());
        assertEquals(now.toLocalDate(), today.toLocalDate());
        assertFalse(today.isAfter(now));
    }

    @Test
    @DisplayName("format 输出 REST 约定的 yyyy-MM-dd HH:mm:ss")
    void formatUsesRestPattern() {
        assertEquals("2025-06-15 23:06:40", TimeUtil.format(SHANGHAI_TIME));
        assertNull(TimeUtil.format(null));
    }
}
