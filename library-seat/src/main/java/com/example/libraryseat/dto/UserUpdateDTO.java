package com.example.libraryseat.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人资料入参（小程序 docs/04 §4.3，规范 §13 未列出但小程序必需）。
 *
 * <p>只允许改 nickname 和 avatar_url。openid / created_at / updated_at /
 * rfid_uid / rfid_bind_time 全部由后端维护，即使前端传了也忽略。
 *
 * <p>不加 @NotBlank：允许把昵称清空，也允许只传其中一个字段。
 * 语义上区分两种"空"：<b>null = 这个字段不改</b>，<b>"" = 把它清空</b>。
 * 小程序 {@code pages/mine/index.js} 保存时对昵称做了 {@code (... || '').trim()}，
 * 所以清空昵称传过来的正是空串，能被正确落库。
 *
 * <p>@Size 的上限照抄 DDL（nickname VARCHAR(64)、avatar_url VARCHAR(255)）。
 * 不加的话超长值会一路走到 MySQL 报 data truncation，
 * 被全局异常处理兜成 500，用户只看到"服务器错误"。
 */
@Data
public class UserUpdateDTO {

    @Size(max = 64, message = "长度不能超过 64 个字符")
    private String nickname;

    @Size(max = 255, message = "长度不能超过 255 个字符")
    private String avatarUrl;
}
