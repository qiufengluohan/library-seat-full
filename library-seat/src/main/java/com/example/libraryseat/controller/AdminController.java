package com.example.libraryseat.controller;

import com.example.libraryseat.common.Result;
import com.example.libraryseat.dto.LoginDTO;
import com.example.libraryseat.service.AdminService;
import com.example.libraryseat.service.StatisticsService;
import com.example.libraryseat.vo.DashboardVO;
import com.example.libraryseat.vo.LoginVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员登录 + 首页看板。编码规范 §13。
 *
 * <p>{@code /api/admin/login} 在 {@code config.WebMvcConfig} 里被 exclude，
 * 其余 {@code /api/admin/**} 全部要求 token 里 role=ADMIN（{@code JwtInterceptor}）。
 *
 * <p>单管理员、无 RBAC（§35 / §49），所以这里没有任何权限列表要返回；
 * LoginVO 也刻意不含 password 字段 —— 密码只能存在于 admin_user 表里，
 * 一次都不许出网。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final StatisticsService statisticsService;

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(adminService.login(dto));
    }

    /**
     * 首页看板：座位状态分布 + 设备在线情况 + 当天四项计数，一次请求全给。
     *
     * <p>合成一个接口而不是让管理端打四次，是因为这四组数字必须来自
     * <b>同一个"今天"的起点</b>：分开请求时如果跨过零点，
     * "今日预约"和"今日违规"就会分属两天，看板上出现自相矛盾的数字。
     */
    @GetMapping("/dashboard")
    public Result<DashboardVO> dashboard() {
        return Result.success(statisticsService.dashboard());
    }
}
