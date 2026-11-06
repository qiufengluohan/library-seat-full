package com.example.libraryseat.vo;

import com.example.libraryseat.entity.RfidUser;
import com.example.libraryseat.entity.User;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学生资料返回（小程序 docs/04 §4.2）。
 *
 * <p>由 user 表和 rfid_user 表拼成。rfid 两个字段可空 ——
 * 未绑卡时返回 null，小程序显示"暂无"。
 *
 * <p><b>这里刻意没有 password 字段</b>：对外的任何结构都不带密码哈希，
 * 多加一个字段就等于把 BCrypt 串送到前端。要加字段前先想清楚这一点。
 *
 * <p>规范 §11 没定义这个 VO，但小程序的"我的"页和"签到"页都要读它，
 * 属于 docs/04 明确列出的补充接口。
 */
@Data
public class UserVO {

    private Long userId;

    /**
     * 学号，账号标识。前端「我的」页显示它。
     */
    private String studentNo;

    private String nickname;

    private String avatarUrl;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private String rfidUid;

    private LocalDateTime rfidBindTime;

    public static UserVO of(User user, RfidUser rfidUser) {
        UserVO vo = new UserVO();
        vo.userId = user.getId();
        vo.studentNo = user.getStudentNo();
        vo.nickname = user.getNickname();
        vo.avatarUrl = user.getAvatarUrl();
        vo.createdAt = user.getCreatedAt();
        vo.updatedAt = user.getUpdatedAt();
        if (rfidUser != null) {
            vo.rfidUid = rfidUser.getRfidUid();
            vo.rfidBindTime = rfidUser.getBindTime();
        }
        return vo;
    }
}
