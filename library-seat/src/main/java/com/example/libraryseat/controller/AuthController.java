package com.example.libraryseat.controller;

import com.example.libraryseat.common.Result;
import com.example.libraryseat.dto.StudentLoginDTO;
import com.example.libraryseat.dto.StudentRegisterDTO;
import com.example.libraryseat.dto.UserUpdateDTO;
import com.example.libraryseat.security.AuthContext;
import com.example.libraryseat.service.UserService;
import com.example.libraryseat.vo.StudentLoginVO;
import com.example.libraryseat.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 学生端身份接口：注册 / 登录 + 我的资料。编码规范 §13 / §14.1。
 *
 * <h2>账号体系</h2>
 * <p>
 * 学生自己注册（学号 + 密码），之后用学号密码登录。密码在后端 BCrypt 校验，
 * 前端不做任何哈希。本系统<b>不接微信登录</b>，所以没有 wechat-login 之类的入口，
 * 也就完全不涉及微信公众平台的 IP 白名单。
 *
 * <h2>为什么类上没有 @RequestMapping</h2>
 * <p>
 * 这个类同时挂着 {@code /api/auth/**} 和 {@code /api/users/me} 两个前缀。
 * 类级别的路径会和方法级别的路径<b>拼接</b>，写成 {@code @RequestMapping("/api/auth")}
 * 之后，{@code /api/users/me} 就会变成 {@code /api/auth/api/users/me} —— 编译不报错，
 * 启动也不报错，只有小程序点"保存资料"时才 404。所以这里方法上全写绝对路径。
 *
 * <h2>为什么"我的资料"在这个类里</h2>
 * <p>
 * §7 的 controller 清单是固定的十个，没有 UserController。
 * {@code /api/users/me} 属于"当前登录人自己"这条身份线，和登录放一起最贴；
 * 而且登录响应本身就是 UserVO 的扁平超集，两者共用一套字段约定。
 *
 * <p>userId 一律取 {@link AuthContext}，<b>绝不接受前端传 userId</b>：
 * 一旦可以从请求体里指定改谁的资料，这个接口就变成了越权改任意用户。
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * 免鉴权（{@code config.WebMvcConfig} 里 exclude），否则没人能注册。
     */
    @PostMapping("/api/auth/register")
    public Result<StudentLoginVO> register(@Valid @RequestBody StudentRegisterDTO dto) {
        return Result.success(userService.register(dto));
    }

    /**
     * 免鉴权，理由同上。
     */
    @PostMapping("/api/auth/login")
    public Result<StudentLoginVO> login(@Valid @RequestBody StudentLoginDTO dto) {
        return Result.success(userService.login(dto));
    }

    @GetMapping("/api/users/me")
    public Result<UserVO> profile() {
        return Result.success(userService.getProfile(AuthContext.userId()));
    }

    @PutMapping("/api/users/me")
    public Result<UserVO> updateProfile(@Valid @RequestBody UserUpdateDTO dto) {
        return Result.success(userService.updateProfile(AuthContext.userId(), dto));
    }
}
