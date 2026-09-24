package com.example.libraryseat.vo;

import com.example.libraryseat.entity.Reservation;
import com.example.libraryseat.entity.Seat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预约记录返回，编码规范 §11.4 + 小程序 docs/04 §5 的展示字段。
 *
 * <p>{@code area} / {@code floor} / {@code seatNo} 不在规范 §11.4 里，是 docs/04 明确
 * 要求的补充：我的预约、签到页、预约详情都要显示"阅览室 + 楼层 + 座位号"。
 * 不加的话小程序得为每条记录再调一次 /api/seats 去关联。
 *
 * <p>{@code reserveExpireAt} / {@code leaveExpireAt} 也必须返回 ——
 * 小程序的签到倒计时直接用这两个值算，不在前端硬编码 15/30 分钟。
 *
 * <p><b>{@code /api/reservations/current} 也用这个 VO，没有单独做规范 §11.5 的
 * CurrentReservationVO。</b>小程序把三个预约接口都过一遍 {@code normReservation}，
 * 它读的主键字段叫 {@code id}；§11.5 那个 VO 写的是 {@code reservationId}，
 * 归一化之后 id 会变成 0，取消/离座/回座就会打到 {@code /api/reservations/0/cancel} 上。
 * 本 VO 是 §11.5 的严格超集，多返回几个字段没有副作用，少一个字段名对不上就是 404。
 *
 * <p>{@code status} 是<b>字符串</b>（RESERVED/USING/...），和 SeatVO 里那个
 * 数字 status 不是一回事，别混。
 */
@Data
public class ReservationVO {

    private Long id;

    private Long seatId;

    private String seatCode;

    private String area;

    private Integer floor;

    private String seatNo;

    private String status;

    private LocalDateTime reserveTime;

    private LocalDateTime signTime;

    private LocalDateTime leaveTime;

    private LocalDateTime returnTime;

    private LocalDateTime releaseTime;

    private LocalDateTime reserveExpireAt;

    private LocalDateTime leaveExpireAt;

    public static ReservationVO of(Reservation reservation, Seat seat) {
        ReservationVO vo = new ReservationVO();
        vo.id = reservation.getId();
        vo.seatId = reservation.getSeatId();
        vo.status = reservation.getStatus();
        vo.reserveTime = reservation.getReserveTime();
        vo.signTime = reservation.getSignTime();
        vo.leaveTime = reservation.getLeaveTime();
        vo.returnTime = reservation.getReturnTime();
        vo.releaseTime = reservation.getReleaseTime();
        vo.reserveExpireAt = reservation.getReserveExpireAt();
        vo.leaveExpireAt = reservation.getLeaveExpireAt();
        if (seat != null) {
            vo.seatCode = seat.getSeatCode();
            vo.area = seat.getArea();
            vo.floor = seat.getFloor();
            vo.seatNo = seatNoOf(seat.getSeatCode());
        }
        return vo;
    }

    /**
     * A4-101 → 101。取最后一段，因为座位号本身可能带 '-'。
     * 没有 '-' 时原样返回，不要返回 null —— 小程序会把它直接渲染到页面上。
     */
    public static String seatNoOf(String seatCode) {
        if (seatCode == null) {
            return null;
        }
        int idx = seatCode.lastIndexOf('-');
        return idx >= 0 && idx < seatCode.length() - 1 ? seatCode.substring(idx + 1) : seatCode;
    }
}
