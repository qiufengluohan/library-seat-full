package com.example.libraryseat.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 微信登录返回（小程序 docs/04 §4.1）。
 *
 * <p>= LoginVO + UserVO 拍平。小程序登录后一次性拿到 token 和用户信息缓存到本地，
 * 不再补一次 /api/users/me，所以这里必须是扁平结构，不能嵌一层 user 对象。
 *
 * <p>规范 §11.1 的 LoginVO 只有 token/userId/nickname，不够小程序用，
 * 因此单独建这个类而不是往 LoginVO 上塞字段 —— 管理员登录不该返回 openid。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WechatLoginVO extends UserVO {

    private String token;

    public static WechatLoginVO of(String token, UserVO user) {
        WechatLoginVO vo = new WechatLoginVO();
        vo.token = token;
        vo.setUserId(user.getUserId());
        vo.setOpenid(user.getOpenid());
        vo.setNickname(user.getNickname());
        vo.setAvatarUrl(user.getAvatarUrl());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setUpdatedAt(user.getUpdatedAt());
        vo.setRfidUid(user.getRfidUid());
        vo.setRfidBindTime(user.getRfidBindTime());
        return vo;
    }
}
