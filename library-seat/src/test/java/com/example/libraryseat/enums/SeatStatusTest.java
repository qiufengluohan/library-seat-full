package com.example.libraryseat.enums;

import com.example.libraryseat.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 座位状态测试。
 *
 * <p>{@code fromReservation} 这条映射在签到、暂离、回座、离座、强制释放、
 * 告警恢复六处都要用。它错一处，表现就是"订单已完成但座位还显示使用中"，
 * 而这种前后不一致在页面上很难归因，所以整张表钉死在这里。
 *
 * <p>另外钉住<b>ALARM 不在映射结果里</b>：告警是叠加在业务状态之上的临时覆盖，
 * 恢复时靠这个方法把原状态还原回去。哪天有人给它加一条
 * {@code USING -> ALARM}，告警恢复就会变成"恢复成告警"。
 */
class SeatStatusTest {

    @Test
    @DisplayName("五个状态码与前端约定的数字一一对应")
    void codesMatchFrontendContract() {
        assertEquals(0, SeatStatus.FREE.getCode());
        assertEquals(1, SeatStatus.RESERVED.getCode());
        assertEquals(2, SeatStatus.USING.getCode());
        assertEquals(3, SeatStatus.AWAY.getCode());
        // 小程序的红色闪烁就是按 status===4 触发的，改这个数字等于改前端
        assertEquals(4, SeatStatus.ALARM.getCode());
    }

    @Test
    @DisplayName("of：数字还原成枚举")
    void ofRestoresEnum() {
        assertEquals(SeatStatus.FREE, SeatStatus.of(0));
        assertEquals(SeatStatus.ALARM, SeatStatus.of(4));
    }

    @Test
    @DisplayName("of：非法数字抛 400，不返回 null 让调用方 NPE")
    void ofRejectsUnknownCode() {
        assertThrows(BusinessException.class, () -> SeatStatus.of(9));
        assertThrows(BusinessException.class, () -> SeatStatus.of(null));
    }

    @Test
    @DisplayName("fromReservation：三个进行中状态一一对应")
    void fromReservationMapsActiveStates() {
        assertEquals(SeatStatus.RESERVED, SeatStatus.fromReservation(ReservationStatus.RESERVED));
        assertEquals(SeatStatus.USING, SeatStatus.fromReservation(ReservationStatus.USING));
        assertEquals(SeatStatus.AWAY, SeatStatus.fromReservation(ReservationStatus.AWAY));
    }

    @Test
    @DisplayName("fromReservation：三个终态和'没有订单'都回 FREE")
    void fromReservationMapsTerminalStatesToFree() {
        assertEquals(SeatStatus.FREE, SeatStatus.fromReservation(ReservationStatus.COMPLETED));
        assertEquals(SeatStatus.FREE, SeatStatus.fromReservation(ReservationStatus.CANCELLED));
        assertEquals(SeatStatus.FREE, SeatStatus.fromReservation(ReservationStatus.TIMEOUT));
        assertEquals(SeatStatus.FREE, SeatStatus.fromReservation(null));
    }

    @Test
    @DisplayName("fromReservation 永远不会返回 ALARM")
    void fromReservationNeverReturnsAlarm() {
        for (ReservationStatus status : ReservationStatus.values()) {
            assertNotEquals(SeatStatus.ALARM, SeatStatus.fromReservation(status),
                    "订单状态 " + status + " 不应映射成 ALARM");
        }
    }
}
