package com.example.libraryseat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预约 / 使用记录，编码规范 §8.4 / §9.4。
 *
 * <p>四个时间字段对应状态机的四次跃迁：sign（RESERVED→USING）、leave（USING→AWAY）、
 * return（AWAY→USING）、release（USING→COMPLETED）。两个 expire 字段驱动定时任务，
 * 超时判断只看它们，不要用"当前时间 - reserve_time"现算，否则改超时时长要动代码。
 *
 * <p>{@code status} 存的是字符串枚举名，见
 * {@link com.example.libraryseat.enums.ReservationStatus}。
 */
@Data
@TableName("reservation")
public class Reservation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long seatId;

    private String status;

    private LocalDateTime reserveTime;

    private LocalDateTime signTime;

    private LocalDateTime leaveTime;

    private LocalDateTime returnTime;

    private LocalDateTime releaseTime;

    /** reserve_time + 15 分钟。RESERVED 超过它未签到 → TIMEOUT（§24）。 */
    private LocalDateTime reserveExpireAt;

    /** leave_time + 30 分钟。AWAY 超过它未回座 → COMPLETED（§24）。回座时清空。 */
    private LocalDateTime leaveExpireAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
