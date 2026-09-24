package com.example.libraryseat.service;

import com.example.libraryseat.dto.LoginDTO;
import com.example.libraryseat.vo.LoginVO;

/**
 * 管理员登录。编码规范 §19 / §35 / §36。
 *
 * <p><b>单管理员，不做 RBAC，不做多管理员</b>（方案 §49 明确列为不做）。
 * 不要往这里加角色表、权限树、菜单配置。
 */
public interface AdminService {

    /**
     * 用户名 + 密码登录。
     *
     * <p>密码用 BCrypt 校验（§35），<b>绝不能在 Java 代码里写明文密码</b>，
     * 也不要做任何前端哈希 —— 前端传原文，后端 matches()。
     *
     * @throws com.example.libraryseat.common.BusinessException 用户名或密码错误时抛 401。
     *         两种情况用同一句话，否则接口会变成用户名枚举器。
     */
    LoginVO login(LoginDTO dto);
}
