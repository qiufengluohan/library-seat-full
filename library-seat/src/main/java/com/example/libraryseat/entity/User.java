package com.example.libraryseat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学生用户，编码规范 §8.1 / §9.1。由微信小程序登录时自动创建。
 *
 * <p>账号唯一标识是 {@code openid}。微信官方不提供获取微信号的接口，
 * 所以不要用 nickname 做任何身份判断（小程序 docs/04 §4.1）。
 */
@Data
@TableName("`user`")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String openid;

    /** 默认为空，小程序展示为"微信用户"，用户可在"我的"页自行修改。 */
    private String nickname;

    private String avatarUrl;

    /** 由数据库默认值写入，这就是"注册时间"，代码里不要手动赋值。 */
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
