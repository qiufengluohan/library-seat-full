package com.example.libraryseat.enums;

import com.example.libraryseat.common.BusinessException;
import lombok.Getter;

/**
 * 座位业务状态，编码规范 §5.1。四端共用同一套数字，前端不得自行推算。
 *
 * <p>设备离线<b>不是</b> SeatStatus（§5.3）。离线由 {@code online} 字段单独表达，
 * 前端在原状态上叠加"离线"角标，不覆盖颜色（§39）。
 */
@Getter
public enum SeatStatus {

    FREE(0, "空闲"),
    RESERVED(1, "已预约"),
    USING(2, "使用中"),
    AWAY(3, "暂离"),
    ALARM(4, "异常告警");

    private final int code;
    private final String label;

    SeatStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public static SeatStatus of(Integer code) {
        if (code != null) {
            for (SeatStatus s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
        }
        throw BusinessException.badRequest("非法的座位状态: " + code);
    }

    /**
     * 由订单状态推出座位应该处于哪个业务状态。
     *
     * <p>这条映射在签到、暂离、回座、离座、强制释放、告警恢复六处都要用，
     * 所以放在枚举上，不要每个 Service 自己 switch 一遍 ——
     * 抄错一处就会出现"订单已 COMPLETED 但座位还显示使用中"这种对不上的状态。
     *
     * <p>订单的三个终态（COMPLETED / CANCELLED / TIMEOUT）和"没有订单"
     * 都对应 FREE：座位没人用了就该是空闲。
     *
     * <p><b>ALARM 不在映射结果里</b>：告警是叠加在业务状态之上的临时覆盖，
     * 恢复时用本方法把原状态还原回去（见 SeatService.statusFromReservation）。
     */
    public static SeatStatus fromReservation(ReservationStatus status) {
        if (status == null) {
            return FREE;
        }
        return switch (status) {
            case RESERVED -> RESERVED;
            case USING -> USING;
            case AWAY -> AWAY;
            case COMPLETED, CANCELLED, TIMEOUT -> FREE;
        };
    }
}
