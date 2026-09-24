package com.example.libraryseat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 管理员登录入参，编码规范 §10.2。密码在后端用 BCrypt 校验，前端不做任何哈希。 */
@Data
public class LoginDTO {

    @NotBlank(message = "不能为空")
    private String username;

    @NotBlank(message = "不能为空")
    private String password;
}
