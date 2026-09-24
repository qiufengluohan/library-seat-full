package com.example.libraryseat.vo;

import com.example.libraryseat.entity.Seat;
import com.example.libraryseat.entity.SeatShadow;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备返回，编码规范 §11.6。管理端"设备管理"页专用。
 *
 * <p>{@code deviceId} 是 OneNET 设备名称（SEAT_001），管理端拿它当路径参数
 * 调 /api/admin/devices/{id}/config 和 /buzzer，所以它必须和 seat 表里的
 * device_id 完全一致。
 *
 * <p>共享读卡器 READER_01 不占座位，不会出现在这个列表里 ——
 * 本 VO 的 seatId / seatCode 是必填语义，设备列表是按座位表驱动的。
 */
@Data
public class DeviceVO {

    private String deviceId;

    private Long seatId;

    private String seatCode;

    private Boolean online;

    private Integer pressureAdc;

    private Boolean pirState;

    private Boolean alarmFlag;

    private LocalDateTime lastReportAt;

    /**
     * 设备列表按座位表驱动：一个座位一台设备，device_id 取 seat.device_id。
     *
     * <p>影子行可能不存在（设备从没上报过）。这时 online / alarmFlag 给 false，
     * 而 pressureAdc / pirState <b>保持 null</b>，不要填 0 ——
     * 管理端表格里"空白"和"读数为 0"是两件不同的事，
     * 填 0 会让人以为设备在线且真的测到了零压力。
     */
    public static DeviceVO of(Seat seat, SeatShadow shadow) {
        DeviceVO vo = new DeviceVO();
        vo.deviceId = seat.getDeviceId();
        vo.seatId = seat.getId();
        vo.seatCode = seat.getSeatCode();
        if (shadow != null) {
            vo.online = Integer.valueOf(1).equals(shadow.getOnline());
            vo.alarmFlag = Integer.valueOf(1).equals(shadow.getAlarmFlag());
            vo.pressureAdc = shadow.getPressureAdc();
            vo.pirState = shadow.getPirState() == null
                    ? null
                    : Integer.valueOf(1).equals(shadow.getPirState());
            vo.lastReportAt = shadow.getLastReportAt();
        } else {
            vo.online = false;
            vo.alarmFlag = false;
        }
        return vo;
    }
}
