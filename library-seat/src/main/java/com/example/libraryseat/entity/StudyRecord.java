package com.example.libraryseat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习时长记录，编码规范 §8.7 / §9.7。
 *
 * <p>一次完整的 USING 周期一条，{@code reservationId} 是 UNIQUE ——
 * 这既是"一个预约只结算一次"的库层保证，也让重复结算直接报错而不是写脏数据。
 *
 * <p>暂离阶段<b>不拆分</b>（§43 / §23）：start = sign_time，end = release_time，
 * 中间出去多久都算在学习时长里。毕业设计阶段刻意不做精确拆分。
 */
@Data
@TableName("study_record")
public class StudyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long seatId;

    private Long reservationId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer durationMinutes;

    private LocalDateTime createdAt;
}
