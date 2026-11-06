package com.example.libraryseat.service;

import com.example.libraryseat.dto.StudentLoginDTO;
import com.example.libraryseat.dto.StudentRegisterDTO;
import com.example.libraryseat.dto.UserUpdateDTO;
import com.example.libraryseat.entity.User;
import com.example.libraryseat.vo.StudentLoginVO;
import com.example.libraryseat.vo.UserVO;

/**
 * 用户注册、登录与资料。编码规范 §19 / §14.1。
 *
 * <p>账号体系是「学号 + 密码」：学生自助注册（{@link #register}），
 * 之后用学号密码登录（{@link #login}）。密码一律经 BCrypt 存储与比对（§35），
 * 明文只存在于请求体的那一次调用里，不落库、不进日志、不进任何响应体。
 *
 * <p>放在 UserService 而不是单开一个 AuthService：§7 的服务清单是固定的十二个，
 * 里面没有 AuthService，而 {@link AdminService#login} 正是管理员侧的对称入口。
 */
public interface UserService {

    /**
     * 学生登录（{@code POST /api/auth/login}）：查学号 → BCrypt 比对 → 签 JWT。
     *
     * <p>「学号不存在」与「密码错误」<b>必须返回同一句话</b>，否则这个接口
     * 就变成了学号枚举器，见实现类。
     */
    StudentLoginVO login(StudentLoginDTO dto);

    /**
     * 学生注册（{@code POST /api/auth/register}）：查重 → BCrypt 加密 → 建档 → 直接签 JWT。
     *
     * <p>注册成功直接返回登录态（而不是"请去登录页"）：少一次往返。
     * 返回体与 {@link #login} 完全一致，小程序侧共用同一套解析。
     */
    StudentLoginVO register(StudentRegisterDTO dto);

    /**
     * 按主键取用户。
     *
     * @return 不存在时返回 null；需要"必须存在"语义的调用方自己抛 404，
     * 因为有些场景（比如给违规记录补昵称）查不到就该留空而不是报错
     */
    User getById(Long userId);

    /**
     * 按学号取用户，不存在返回 null。登录与注册查重都走它。
     */
    User getByStudentNo(String studentNo);

    /**
     * 我的资料。带 rfid_uid / rfid_bind_time，小程序"我的"页一次拿全
     */
    UserVO getProfile(Long userId);

    /**
     * 改昵称 / 头像。
     *
     * <p>只有这两个字段可改 —— 学号是身份标识、密码走单独的入口，
     * userId 由 token 决定，都不接受前端传入。
     */
    UserVO updateProfile(Long userId, UserUpdateDTO dto);
}
