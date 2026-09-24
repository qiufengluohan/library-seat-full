package com.example.libraryseat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务时间参数，编码规范 §28：统一放 application.yml，不做动态配置。
 *
 * <p>小程序 {@code utils/config.js} 的 {@code business} 块里写着同样的
 * 15 / 30 / 120，那只是给学生看的文案，<b>真正的判定一律以后端为准</b>。
 * 改了这里的值不需要改小程序，但倒计时会和实际释放时刻不一致，
 * 所以两处最好一起改。
 */
@Data
@Component
@ConfigurationProperties(prefix = "seat")
public class SeatProperties {

    /** RESERVED 超过该时长未刷卡签到 -> TIMEOUT -> 座位回 FREE（§24） */
    private int reserveTimeoutMinutes = 15;

    /** AWAY 超过该时长未回座 -> COMPLETED -> 座位回 FREE（§24） */
    private int awayTimeoutMinutes = 30;

    /**
     * now - last_report_at 超过该值判定 online = false。
     * 设备心跳 60 秒一次，120 秒等于允许丢一个包（§25）。
     */
    private int deviceOfflineTimeoutSeconds = 120;

    /** 下发给设备的默认 ADC 阈值，实际值以设备端实验结果为准 */
    private int defaultAdcThreshold = 2000;
}
