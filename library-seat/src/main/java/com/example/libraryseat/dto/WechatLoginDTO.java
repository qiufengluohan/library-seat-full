package com.example.libraryseat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 微信登录入参，编码规范 §10.1。
 *
 * <p>小程序 {@code wx.login()} 拿到的 code 只能用一次，且 5 分钟内有效。
 */
@Data
public class WechatLoginDTO {

    @NotBlank(message = "不能为空")
    private String code;
}
