package com.example.libraryseat.controller;

import com.example.libraryseat.common.Result;
import com.example.libraryseat.dto.UserUpdateDTO;
import com.example.libraryseat.dto.WechatLoginDTO;
import com.example.libraryseat.security.AuthContext;
import com.example.libraryseat.service.UserService;
import com.example.libraryseat.vo.UserVO;
import com.example.libraryseat.vo.WechatLoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生端身份接口：微信登录 + 我的资料。编码规范 §13 / §14.1。
 *
 * <h2>为什么类上没有 @RequestMapping</h2>
 *
 * 这个类同时挂着 {@code /api/auth/wechat-login} 和 {@code /api/users/me} 两个前缀。
 * 类级别的路径会和方法级别的路径<b>拼接</b>，写成 {@code @RequestMapping("/api/auth")}
 * 之后，{@code /api/users/me} 就会变成 {@code /api/auth/api/users/me} —— 编译不报错，
 * 启动也不报错，只有小程序点"保存资料"时才 404。所以这里方法上全写绝对路径。
 *
 * <h2>为什么"我的资料"在这个类里</h2>
 *
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

    /** 免鉴权（{@code config.WebMvcConfig} 里 exclude），否则没人能登录。 */
    @PostMapping("/api/auth/wechat-login")
    public Result<WechatLoginVO> wechatLogin(@Valid @RequestBody WechatLoginDTO dto) {
        return Result.success(userService.loginByCode(dto));
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
