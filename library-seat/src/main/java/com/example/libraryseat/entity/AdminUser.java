package com.example.libraryseat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员，编码规范 §8.2 / §9.2。单管理员，不做角色表和权限树（§35）。
 */
@Data
@TableName("admin_user")
public class AdminUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /**
     * BCrypt 哈希，编码规范 §35 明确要求，禁止明文。
     * 这个字段永远不能出现在任何 VO 里。
     */
    private String password;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
