package com.example.libraryseat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RFID 与学生的绑定，编码规范 §8.5 / §9.5。
 *
 * <p>共享读卡器只上报 rfid_uid，由这张表反查出 user_id（方案 §26）。
 * uid 和 userId 在库层都是 UNIQUE：一张卡只属于一个人，一个人只绑一张卡。
 * 绑定时若任一冲突要回 409，让管理端提示而不是静默覆盖。
 */
@Data
@TableName("rfid_user")
public class RfidUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String rfidUid;

    private Long userId;

    private LocalDateTime bindTime;

    private LocalDateTime createdAt;
}
