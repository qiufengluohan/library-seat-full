package com.example.libraryseat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 学生注册入参（{@code POST /api/auth/register}）。
 *
 * <p>注册只收学号 + 密码（昵称可选）。这里<b>不收</b>手机号、邮箱、真实姓名 ——
 * 系统里没这些字段，收了也没地方存，反而多一份个人信息。
 *
 * <p>密码强度只卡长度不卡组合：本项目是校园内网场景，强行要求
 * "大写+小写+数字+符号"只会把密码逼成写在便签上的那种。真正管用的是
 * 走 BCrypt 存（§35）和不在日志里打密码。
 */
@Data
public class StudentRegisterDTO {

    /**
     * 学号即账号。限定字母数字是为了避免全角字符、空格、表情符号
     * 这类"看着一样但查不出来"的输入 —— 登录时多一个空格就永远登不上。
     */
    @NotBlank(message = "不能为空")
    @Pattern(regexp = "^[A-Za-z0-9]{4,32}$", message = "只能由 4~32 位字母或数字组成")
    private String studentNo;

    @NotBlank(message = "不能为空")
    @Size(min = 6, max = 32, message = "长度需在 6~32 位之间")
    private String password;

    /**
     * 可选。留空则由前端按 config.business.defaultNickname「同学」展示。
     */
    @Size(max = 64, message = "最长 64 个字符")
    private String nickname;
}
