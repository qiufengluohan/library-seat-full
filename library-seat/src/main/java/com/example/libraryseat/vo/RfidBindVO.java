package com.example.libraryseat.vo;

import com.example.libraryseat.entity.RfidUser;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RFID 绑定返回。
 *
 * <p>规范 §11 没定义这个 VO（§7 的目录里列了文件名但没给字段），
 * 字段按 rfid_user 表 + user 表推出来。{@code studentName} 取自 user.nickname，
 * 可能为空 —— 微信拿不到真实姓名，管理端列表要能容忍 null 并退回显示 user_id。
 */
@Data
public class RfidBindVO {

    private Long id;

    private String rfidUid;

    private Long userId;

    private String studentName;

    private LocalDateTime bindTime;

    public static RfidBindVO of(RfidUser entity, String studentName) {
        RfidBindVO vo = new RfidBindVO();
        vo.id = entity.getId();
        vo.rfidUid = entity.getRfidUid();
        vo.userId = entity.getUserId();
        vo.studentName = studentName;
        vo.bindTime = entity.getBindTime();
        return vo;
    }
}
