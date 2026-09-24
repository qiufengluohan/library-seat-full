package com.example.libraryseat.vo;

import com.example.libraryseat.entity.Seat;
import com.example.libraryseat.entity.SeatShadow;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 座位状态返回，编码规范 §11.2。小程序和 Web 管理端共用这一个结构。
 *
 * <p>三个关键字段的来源和铁律：
 * <ul>
 *   <li>{@code status} —— seat 表，<b>后端合成的业务状态</b>。前端不得根据
 *       pressure_adc / pir_state 自己推算（方案 §5、§53）。</li>
 *   <li>{@code alarm} —— seat_shadow.alarm_flag，设备本地判定的假占座。</li>
 *   <li>{@code online} —— seat_shadow.online，后端算的。<b>离线不覆盖 status</b>，
 *       前端在原状态颜色上叠加"离线"角标（§5.3、§39）。</li>
 * </ul>
 *
 * <p>{@code studentName} / {@code reserveTime} / {@code signTime} 只有管理端列表用得上，
 * 学生端拿到的是别人的昵称 —— 但座位是公开的，管理端座位网格要显示"谁在坐"，
 * 而学生端小程序不读这三个字段，所以合在一个 VO 里不会造成信息泄露问题。
 */
@Data
public class SeatVO {

    private Long seatId;

    private String seatCode;

    private String area;

    private Integer floor;

    private Integer status;

    private Boolean alarm;

    private Boolean online;

    private String studentName;

    private LocalDateTime reserveTime;

    private LocalDateTime signTime;

    /**
     * 影子行可能还不存在（设备从没上报过）。这时候 alarm/online 都要有确定值：
     * alarm=false，online=false —— 从没连上的设备不该显示成在线。
     */
    public static SeatVO of(Seat seat, SeatShadow shadow) {
        SeatVO vo = new SeatVO();
        fillBase(vo, seat, shadow);
        return vo;
    }

    /**
     * 基础字段的唯一一份映射，{@link SeatDetailVO} 复用它。
     * 拆出来是因为详情 VO 继承自本类，两边各抄一遍的话，
     * 以后给 SeatVO 加字段一定会漏掉详情接口。
     */
    static void fillBase(SeatVO vo, Seat seat, SeatShadow shadow) {
        vo.seatId = seat.getId();
        vo.seatCode = seat.getSeatCode();
        vo.area = seat.getArea();
        vo.floor = seat.getFloor();
        vo.status = seat.getStatus();
        vo.alarm = shadow != null && Integer.valueOf(1).equals(shadow.getAlarmFlag());
        vo.online = shadow != null && Integer.valueOf(1).equals(shadow.getOnline());
    }
}
