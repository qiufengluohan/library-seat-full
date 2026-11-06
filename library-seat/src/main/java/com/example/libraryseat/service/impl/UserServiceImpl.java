package com.example.libraryseat.service.impl;

import com.example.libraryseat.common.BusinessException;
import com.example.libraryseat.dto.StudentLoginDTO;
import com.example.libraryseat.dto.StudentRegisterDTO;
import com.example.libraryseat.dto.UserUpdateDTO;
import com.example.libraryseat.entity.User;
import com.example.libraryseat.mapper.RfidUserMapper;
import com.example.libraryseat.mapper.UserMapper;
import com.example.libraryseat.service.UserService;
import com.example.libraryseat.util.JwtUtil;
import com.example.libraryseat.vo.StudentLoginVO;
import com.example.libraryseat.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final RfidUserMapper rfidUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /**
     * 学生登录。
     *
     * <p>「学号不存在」与「密码错误」返回<b>同一句话、同一个错误码</b> ——
     * 分开提示等于对外提供了一个学号枚举接口：攻击者拿一份学号名单跑一遍，
     * 就能筛出哪些学号真的注册过。日志同理，只记学号不记密码。
     */
    @Override
    public StudentLoginVO login(StudentLoginDTO dto) {
        User user = userMapper.selectByStudentNo(dto.getStudentNo().trim());

        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            log.warn("学生登录失败: studentNo={}", dto.getStudentNo());
            throw BusinessException.unauthorized("学号或密码错误");
        }

        log.info("学生登录成功: userId={}", user.getId());

        // 用 getProfile 重新查一次而不是直接 UserVO.of(user, null)：
        // 登录响应里要带 rfid_uid，小程序"我的"页据此显示"已绑卡"，
        // 少这个字段就得再发一次请求。
        return StudentLoginVO.of(jwtUtil.generate(user.getId(), JwtUtil.ROLE_STUDENT),
                getProfile(user.getId()));
    }

    /**
     * 学生注册。成功后直接签发 token，不要求用户再登一次。
     *
     * <p>这里的"先查再插"只是为了让正常路径给出友好提示，
     * <b>它挡不住并发</b>：两个人同时注册同一个学号时，两次查询都会说"没有"，
     * 然后两条 INSERT 一起冲。真正兜底的是 {@code user.student_no} 上的
     * UNIQUE 索引 —— 后到的那条会被数据库拒绝，转成 DuplicateKeyException，
     * 我们把它翻译成同一句"该学号已注册"。所以两个异常分支的提示必须一致。
     */
    @Override
    public StudentLoginVO register(StudentRegisterDTO dto) {
        String studentNo = dto.getStudentNo().trim();

        if (userMapper.selectByStudentNo(studentNo) != null) {
            throw BusinessException.badRequest("该学号已注册，请直接登录");
        }

        User user = new User();
        user.setStudentNo(studentNo);
        // ⚠️ 明文密码在这里出现两次：
        //   ① 交给 BCrypt 生成哈希，写进 password —— 登录校验只用它；
        //   ② 原样写进 password_plain —— 2026-10-08 应"要在库里看到学生设了什么密码"
        //      而额外加的明文列，是对本项目"禁止明文"口径的有意偏离。
        // 无论哪一份都不许 log。生产部署前删掉 password_plain 整列。
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setPasswordPlain(dto.getPassword());
        // 昵称不填就留 null（而不是空串）：null 表示"没设过"，
        // 前端按 config.business.defaultNickname「同学」兜底展示，
        // 用户在「我的」页随时可以改。
        user.setNickname(dto.getNickname() == null || dto.getNickname().isBlank()
                ? null
                : dto.getNickname().trim());

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw BusinessException.badRequest("该学号已注册，请直接登录");
        }

        log.info("新学生注册成功: userId={}", user.getId());

        // 重新查一次拿 created_at：它由数据库默认值写入，
        // insert 之后的实体里这个字段还是 null。
        return StudentLoginVO.of(jwtUtil.generate(user.getId(), JwtUtil.ROLE_STUDENT),
                getProfile(user.getId()));
    }

    @Override
    public User getById(Long userId) {
        return userId == null ? null : userMapper.selectById(userId);
    }

    @Override
    public User getByStudentNo(String studentNo) {
        return (studentNo == null || studentNo.isBlank())
                ? null
                : userMapper.selectByStudentNo(studentNo);
    }

    @Override
    public UserVO getProfile(Long userId) {
        User user = requireUser(userId);
        return UserVO.of(user, rfidUserMapper.findByUserId(userId));
    }

    @Override
    public UserVO updateProfile(Long userId, UserUpdateDTO dto) {
        requireUser(userId);

        User update = new User();
        update.setId(userId);
        boolean changed = false;

        // null = 不改，"" = 清空。MyBatis-Plus 的 updateById 会跳过 null 字段，
        // 所以空串能正常落库，不需要额外的 UpdateWrapper。
        if (dto.getNickname() != null) {
            update.setNickname(dto.getNickname().trim());
            changed = true;
        }
        if (dto.getAvatarUrl() != null) {
            update.setAvatarUrl(dto.getAvatarUrl().trim());
            changed = true;
        }

        if (changed) {
            userMapper.updateById(update);
        }
        return getProfile(userId);
    }

    private User requireUser(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw BusinessException.notFound("用户不存在");
        }
        return user;
    }
}
