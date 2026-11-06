package com.example.libraryseat.service.impl;

import com.example.libraryseat.common.BusinessException;
import com.example.libraryseat.dto.LoginDTO;
import com.example.libraryseat.entity.AdminUser;
import com.example.libraryseat.mapper.AdminUserMapper;
import com.example.libraryseat.service.AdminService;
import com.example.libraryseat.util.JwtUtil;
import com.example.libraryseat.vo.LoginVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final AdminUserMapper adminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public LoginVO login(LoginDTO dto) {
        AdminUser admin = adminUserMapper.selectByUsername(dto.getUsername());

        // 用户不存在、密码错误，必须是同一句话 + 同一个错误码。
        // 分开提示就等于送了一个用户名枚举接口。
        // 日志里也只记 username，绝不记 password（连明文带过来的都不能记）。
        if (admin == null || !passwordEncoder.matches(dto.getPassword(), admin.getPassword())) {
            log.warn("管理员登录失败: username={}", dto.getUsername());
            throw BusinessException.unauthorized("用户名或密码错误");
        }

        log.info("管理员登录成功: username={}, adminId={}", admin.getUsername(), admin.getId());

        LoginVO vo = new LoginVO();
        vo.setToken(jwtUtil.generate(admin.getId(), JwtUtil.ROLE_ADMIN));
        vo.setUserId(admin.getId());
        // admin_user 表没有 nickname 列（单管理员，方案 §49 不做多管理员），
        // 管理端顶栏要显示个名字，就用 username
        vo.setNickname(admin.getUsername());
        return vo;
    }
}
