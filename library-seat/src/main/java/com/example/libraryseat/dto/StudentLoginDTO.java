package com.example.libraryseat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 学生登录入参（{@code POST /api/auth/login}）。
 *
 * <p>密码在<b>后端</b>用 BCrypt 校验，前端不做任何哈希 ——
 * 前端哈希一把等于把哈希当密码用，抓包重放照样能登录，只会给人错觉。
 * 传输侧靠 HTTPS（真机联调见 docs/04 的域名白名单说明）。
 *
 * <p>学号这里不做格式校验，只要求非空：登录时格式再严也挡不住什么，
 * 真正要防的是"这个学号到底存不存在"，那由后端查库回答。格式约束放在注册，
 * 让错误在写入数据之前就暴露。
 */
@Data
public class StudentLoginDTO {

    @NotBlank(message = "不能为空")
    private String studentNo;

    @NotBlank(message = "不能为空")
    private String password;
}
