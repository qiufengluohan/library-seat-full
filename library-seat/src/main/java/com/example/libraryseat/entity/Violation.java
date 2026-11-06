package com.example.libraryseat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 违规 / 异常记录，编码规范 §8.8 / §9.8。
 *
 * <p>{@code type} 只有三类，见 {@link com.example.libraryseat.enums.ViolationType}。
 * 这张表只负责<b>记录</b>：不做黑名单、不做信用分、不做任何自动处罚（方案 §49）。
 *
 * <p>{@code userId} 可空 —— 假占座是设备判定的，那一刻未必知道是谁坐在上面。
 */
@Data
@TableName("violation")
public class Violation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long seatId;

    private String type;

    private String description;

    private LocalDateTime createdAt;
}
