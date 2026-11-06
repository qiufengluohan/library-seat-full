package com.example.libraryseat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学生用户，编码规范 §8.1 / §9.1。由学生自助注册产生。
 *
 * <p>账号唯一标识是 {@code studentNo}（学号）。登录用「学号 + 密码」，
 * 密码比对走 BCrypt（见 {@code service/impl/UserServiceImpl#login}）。
 *
 * <p>⚠️ 这里存了<b>两份</b>密码：
 * <ul>
 *   <li>{@code password} —— BCrypt 哈希，登录校验只用它；</li>
 *   <li>{@code passwordPlain} —— <b>明文</b>，是 2026-10-08 应"要在库里看到学生
 *       到底设了什么密码"而额外加的一列，属于对该项目"禁止明文"口径的<b>有意偏离</b>。</li>
 * </ul>
 * 两者的共同约束：<b>任何返回给前端的 VO 都不许带上它们</b> ——
 * 见 {@code vo/UserVO} 与 {@code vo/StudentLoginVO}，那里刻意都没有这两个字段；
 * 也都不许进日志。生产部署前应把 {@code password_plain} 整列删掉。
 * 昵称只是展示名，不要用它做任何身份判断。
 */
@Data
@TableName("`user`")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 学号。注册时由学生填写，全局唯一（库里有 UNIQUE 约束兜底并发）。
     */
    private String studentNo;

    /**
     * BCrypt 哈希。登录校验唯一依据。禁止出现在日志、响应体、任何 VO 里。
     */
    private String password;

    /**
     * 明文密码。仅用于开发排障与答辩演示（直接看库里学生设了什么密码）。
     *
     * <p>只在注册时写入一次；本系统没有自助改密，所以不存在"改密后忘了同步"的问题。
     * <b>不要</b>把它放进任何 VO，也<b>不要</b>打日志。
     */
    private String passwordPlain;

    /**
     * 注册时可选填；为空时前端按 config.business.defaultNickname「同学」展示。
     */
    private String nickname;

    private String avatarUrl;

    /**
     * 由数据库默认值写入，这就是「注册时间」，代码里不要手动赋值。
     */
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
