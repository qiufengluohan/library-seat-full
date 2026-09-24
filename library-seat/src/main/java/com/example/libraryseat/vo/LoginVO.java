package com.example.libraryseat.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理员登录返回，编码规范 §11.1。
 *
 * <p>不含 password，也不含任何权限列表 —— 单管理员，没有 RBAC（§35）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    private String token;

    private Long userId;

    private String nickname;
}
