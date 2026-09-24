package com.example.libraryseat.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具。
 *
 * <p>全系统统一用 {@link #ZONE} 而不是 JVM 默认时区。{@code application.yml} 里
 * Jackson 的 time-zone 也配的是 Asia/Shanghai，两处必须一致 —— 否则部署到 UTC 的
 * 服务器上，REST 返回的时间字符串和 WebSocket 里的 epoch 秒会差 8 小时，
 * 小程序的倒计时会直接算错。
 *
 * <p>业务代码请调 {@link #now()} 而不是 {@code LocalDateTime.now()}，理由同上。
 */
public final class TimeUtil {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** REST 接口的时间格式。小程序 docs/04 §2.3 要求 yyyy-MM-dd HH:mm:ss。 */
    public static final DateTimeFormatter REST_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 秒与毫秒的分界。1e11 秒是公元 5138 年，1e11 毫秒是 1973 年，
     * 因此大于该值的一定是毫秒。OneNET 规范 §15 给的是秒，
     * 但 Studio 推送实测可能是毫秒，这里两种都吃。
     */
    private static final long MILLIS_THRESHOLD = 100_000_000_000L;

    private TimeUtil() {
    }

    /**
     * 当前时间，<b>截断到整秒</b>。
     *
     * <p>这一句 {@code withNano(0)} 不是为了好看，是为了让"Java 里的值 / 数据库里的值 /
     * REST 返回的字符串"三者恒等。数据库的时间列全是 {@code DATETIME(0)}，MySQL 对超出
     * 精度的小数是<b>四舍五入</b>（{@code :52.6} 存成 {@code :53}），而 Jackson 的
     * {@code yyyy-MM-dd HH:mm:ss} 对同一个值<b>直接截断</b>（{@code :52.6} 写成 {@code :52}）。
     * 一旦某个 VO 里既有回读的时间又有内存里的时间（强制释放就是这种：
     * {@code reserve_time} 回读、{@code release_time} 用 {@code now()}），
     * 就会出现"释放比预约早 1 秒"这种不可能的顺序，管理端算出负时长。
     *
     * <p>副作用只有一个：{@link #minutesBetween} 之类的差值计算变成整秒运算，
     * 本来也不该依赖亚秒精度。
     */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE).withNano(0);
    }

    /**
     * 今天 00:00（按 {@link #ZONE}）。
     *
     * <p>Dashboard 的"今日预约数 / 今日学习人数 / 今日学习分钟数 / 今日违规数"
     * 全部以它为起点。<b>不要</b>在 SQL 里用 CURDATE() 或 NOW() ——
     * 应用跑在 UTC 容器里、MySQL 用 +08:00 时，两边算出的"今天"会差 8 小时，
     * 早上八点后打开 Dashboard 会看到昨天的数据。
     * 起始时间在 Java 侧算好再传进 SQL，就没有这个歧义。
     */
    public static LocalDateTime startOfToday() {
        return now().toLocalDate().atStartOfDay();
    }

    /** 转成 WebSocket 消息里的 epoch 秒（编码规范 §18 的 timestamp 字段）。 */
    public static long toEpochSecond(LocalDateTime time) {
        return time == null ? 0L : time.atZone(ZONE).toEpochSecond();
    }

    public static long toEpochSecondNow() {
        return Instant.now().getEpochSecond();
    }

    /**
     * 解析设备上报的 timestamp，秒和毫秒都接受。
     *
     * @return 解析不出来时返回 null，调用方应退回 {@link #now()}
     */
    public static LocalDateTime fromEpoch(Long timestamp) {
        if (timestamp == null || timestamp <= 0) {
            return null;
        }
        Instant instant = timestamp > MILLIS_THRESHOLD
                ? Instant.ofEpochMilli(timestamp)
                : Instant.ofEpochSecond(timestamp);
        // 毫秒级时间戳同样要落到整秒：设备推来的值会直接写进 last_report_at，
        // 带小数的话又会撞上上面 DATETIME(0) 四舍五入的那条坑。
        return LocalDateTime.ofInstant(instant, ZONE).withNano(0);
    }

    /**
     * 解析文本时间。设备可能上报 epoch 数字字符串，也可能上报 ISO-8601。
     *
     * @return 解析不出来时返回 null
     */
    public static LocalDateTime fromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        try {
            return fromEpoch(Long.parseLong(trimmed));
        } catch (NumberFormatException ignored) {
            // 不是纯数字，按 ISO 试
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(trimmed), ZONE);
        } catch (Exception ignored) {
            // 继续试不带时区的格式
        }
        try {
            return LocalDateTime.parse(trimmed.replace(' ', 'T'));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 学习时长（分钟）。编码规范 §23：暂离阶段不拆分，按整个 USING 周期算。
     *
     * <p>不足一分钟按 0 算而不是 1，避免刷时长；end 早于 start 时返回 0，
     * 设备时钟回拨不该写出负数记录。
     */
    public static int minutesBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        long minutes = Duration.between(start, end).toMinutes();
        return minutes < 0 ? 0 : (int) minutes;
    }

    public static String format(LocalDateTime time) {
        return time == null ? null : REST_FORMATTER.format(time);
    }
}
