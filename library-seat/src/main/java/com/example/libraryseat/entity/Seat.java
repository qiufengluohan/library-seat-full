package com.example.libraryseat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 座位，编码规范 §8.3 / §9.3。
 *
 * <p>{@code deviceId} 存的是 OneNET 的<b>设备名称</b>（如 SEAT_001），不是控制台里那串
 * 数字设备ID（如 2667627332）。OneNET Studio 的属性下发和服务调用接口都是按
 * product_id + device_name 寻址的，数字ID 后端用不到。
 *
 * <p>{@code status} 是业务状态，取值见 {@link com.example.libraryseat.enums.SeatStatus}。
 * 它由后端合成，前端不得根据 pressure_adc / pir_state 自行推算（方案 §5）。
 */
@Data
@TableName("seat")
public class Seat {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String seatCode;

    /** 阅览室名称。不做独立表，前端用 area + floor 归并（小程序 docs/04 §4.4）。 */
    private String area;

    private Integer floor;

    private String deviceId;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
