package com.example.libraryseat.vo;

import com.example.libraryseat.entity.Reservation;
import com.example.libraryseat.entity.Seat;
import com.example.libraryseat.entity.SeatShadow;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 座位详情，编码规范 §11.3 = SeatVO 全部字段 + 设备与当前订单信息。
 *
 * <p>只有管理端调这个接口。§11.3 特意说明"小程序普通座位列表不一定返回
 * pressureAdc"—— 原始传感器数据只在管理端暴露，学生端拿 SeatVO 就够了。
 *
 * <p>注意这里<b>没有 device_id</b>，规范 §11.3 的字段清单里就没有。
 * Web 管理端的座位详情弹窗要显示设备编号，它是拿 /api/admin/devices 的
 * seat_id 反查出来的（见 library-admin/src/views/SeatManage.vue）。
 * 保持和规范一致，不要为了省一次请求在这里加字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SeatDetailVO extends SeatVO {

    private Integer pressureAdc;

    private Boolean pirState;

    private LocalDateTime lastReportAt;

    private Long currentReservationId;

    private Long currentUserId;

    /** 本次进行中订单已学习的分钟数，未签到时为 0。 */
    private Integer studyMinutes;

    /**
     * @param current      该座位当前进行中的订单，座位空着时传 null
     * @param studyMinutes 本次已学习分钟数，未签到传 0
     */
    public static SeatDetailVO of(Seat seat, SeatShadow shadow, Reservation current, int studyMinutes) {
        SeatDetailVO vo = new SeatDetailVO();
        SeatVO.fillBase(vo, seat, shadow);
        if (shadow != null) {
            vo.pressureAdc = shadow.getPressureAdc();
            vo.pirState = shadow.getPirState() == null
                    ? null
                    : Integer.valueOf(1).equals(shadow.getPirState());
            vo.lastReportAt = shadow.getLastReportAt();
        }
        if (current != null) {
            vo.currentReservationId = current.getId();
            vo.currentUserId = current.getUserId();
        }
        vo.studyMinutes = studyMinutes;
        return vo;
    }
}
