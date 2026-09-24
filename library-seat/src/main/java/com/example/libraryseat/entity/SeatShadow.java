package com.example.libraryseat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备影子，编码规范 §8.6 / §9.6，方案 §20。
 *
 * <p>只回答"现在设备是什么状态"，每个座位一行，收到上报就覆盖。
 * <b>不存历史、不存 rfid_uid、不加缓存字段</b> —— 历史归 study_record 和 violation。
 *
 * <p>pirState / alarmFlag / online 在库里是 TINYINT，Java 侧按 §9.6 用 Integer，
 * 转成 VO 时才变 Boolean。别在实体上直接用 Boolean，MyBatis 对 TINYINT 的
 * 映射行为和列定义有关，Integer 最不容易出意外。
 */
@Data
@TableName("seat_shadow")
public class SeatShadow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long seatId;

    private String deviceId;

    private Integer pressureAdc;

    /** 1 = 有人，0 = 无人。 */
    private Integer pirState;

    /** 1 = 设备本地判定的假占座告警。业务状态 ALARM(4) 由它驱动。 */
    private Integer alarmFlag;

    /** 由后端计算，不是设备上报的（方案 §14）。DeviceOfflineTask 每 60 秒刷新。 */
    private Integer online;

    /** 最后一次收到上报的时间，离线判定的唯一依据。 */
    private LocalDateTime lastReportAt;

    private LocalDateTime updatedAt;
}
