package com.example.libraryseat.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 学生登录 / 注册成功后的返回（小程序 docs/04 §4.1）。
 *
 * <p>= LoginVO + UserVO 拍平。小程序登录后一次性拿到 token 和用户信息缓存到本地，
 * 不再补一次 /api/users/me，所以这里必须是扁平结构，不能嵌一层 user 对象。
 *
 * <p>规范 §11.1 的 LoginVO 只有 token/userId/nickname，不够小程序用，
 * 因此单独建这个类而不是往 LoginVO 上塞字段 —— 管理员登录不该返回学号。
 *
 * <p>注册成功也返回这个结构（而不是"注册成功，请去登录"）：
 * 少一次往返，前端拿到就直接进首页。两处的返回体完全一致，
 * 小程序侧可以共用同一套解析逻辑。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StudentLoginVO extends UserVO {

    private String token;

    public static StudentLoginVO of(String token, UserVO user) {
        StudentLoginVO vo = new StudentLoginVO();
        vo.token = token;
        vo.setUserId(user.getUserId());
        vo.setStudentNo(user.getStudentNo());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setUpdatedAt(user.getUpdatedAt());
        vo.setRfidUid(user.getRfidUid());
        vo.setRfidBindTime(user.getRfidBindTime());
        return vo;
    }
}
