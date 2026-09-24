package com.example.libraryseat.enums;

import lombok.Getter;

/**
 * 违规类型，编码规范 §44：只有这三类，不要扩展。
 *
 * <p>黑名单、信用分、积分都在方案 §49 的"明确不做"里，
 * violation 表只负责记录，不做任何处罚逻辑。
 */
@Getter
public enum ViolationType {

    FAKE_OCCUPY("假占座"),
    RESERVATION_TIMEOUT("预约超时"),
    AWAY_TIMEOUT("暂离超时");

    private final String label;

    ViolationType(String label) {
        this.label = label;
    }
}
