package com.example.libraryseat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建预约入参，编码规范 §10.3 / §14.3。
 *
 * <p>只有 seat_id，<b>没有时段概念</b>。约多久由签到后的暂离/离座操作和超时任务决定，
 * 不要往这里加 start_time / end_time。
 */
@Data
public class ReservationCreateDTO {

    @NotNull(message = "不能为空")
    private Long seatId;
}
