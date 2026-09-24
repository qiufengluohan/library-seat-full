package com.example.libraryseat.service.impl;

import com.example.libraryseat.common.BusinessException;
import com.example.libraryseat.common.ErrorCode;
import com.example.libraryseat.config.WechatProperties;
import com.example.libraryseat.dto.UserUpdateDTO;
import com.example.libraryseat.dto.WechatLoginDTO;
import com.example.libraryseat.entity.User;
import com.example.libraryseat.mapper.RfidUserMapper;
import com.example.libraryseat.mapper.UserMapper;
import com.example.libraryseat.service.UserService;
import com.example.libraryseat.util.JsonUtil;
import com.example.libraryseat.util.JwtUtil;
import com.example.libraryseat.vo.UserVO;
import com.example.libraryseat.vo.WechatLoginVO;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** code2Session 的响应体截断长度，日志里不要贴整段（含 session_key）。 */
    private static final int MAX_LOG_BODY = 200;

    private final UserMapper userMapper;
    private final RfidUserMapper rfidUserMapper;
    private final OkHttpClient okHttpClient;
    private final WechatProperties wechatProperties;
    private final JwtUtil jwtUtil;
    private final JsonUtil jsonUtil;

    @PostConstruct
    void reportConfiguration() {
        if (wechatProperties.isConfigured()) {
            return;
        }
        log.warn("""
                微信登录未配置（wechat.app-secret 为空），POST /api/auth/wechat-login 会直接返回错误，\
                小程序端点「微信登录」会看到"微信登录未配置"。配置方式：设置环境变量 WECHAT_APP_SECRET\
                （必要时同时设 WECHAT_APP_ID）后重启；AppSecret 不要写进任何文件或前端仓库。""");
    }

    @Override
    public WechatLoginVO loginByCode(WechatLoginDTO dto) {
        if (dto == null || dto.getCode() == null || dto.getCode().isBlank()) {
            throw BusinessException.badRequest("缺少微信登录 code");
        }
        if (!wechatProperties.isConfigured()) {
            // 这是配置错误，不是用户的错。伪装成"微信登录失败"会让人跑去查小程序，
            // 而真正缺的是环境变量。
            throw new BusinessException(ErrorCode.ERROR,
                    "微信登录未配置：请设置环境变量 WECHAT_APP_SECRET 后重启");
        }

        String openid = exchangeOpenid(dto.getCode().trim());
        User user = findOrCreateByOpenid(openid);
        String token = jwtUtil.generate(user.getId(), JwtUtil.ROLE_STUDENT);

        // 日志里只记 userId，不记 openid：openid 是学生的身份标识，
        // 拿着它能直接反查出是谁，属于个人信息。
        log.info("学生微信登录成功: userId={}", user.getId());

        // 用 getProfile 重新查一次而不是直接 UserVO.of(user, null)：
        // 登录响应里要带 rfid_uid，小程序"我的"页据此显示"已绑卡"，
        // 少这个字段就得再发一次请求。
        return WechatLoginVO.of(token, getProfile(user.getId()));
    }

    /**
     * 调微信 {@code jscode2session} 把一次性 code 换成 openid。
     *
     * <p><b>绝对不能把 url 打进日志</b> —— 它带着 appsecret，
     * 泄露后任何人都能以本小程序的身份换 openid。session_key 同理。
     * 出错时只记 errcode / errmsg，这两个是微信公开的调试信息。
     */
    private String exchangeOpenid(String code) {
        HttpUrl base = HttpUrl.parse(wechatProperties.getCode2SessionUrl());
        if (base == null) {
            throw new BusinessException(ErrorCode.ERROR, "微信登录地址配置有误");
        }
        Request request = new Request.Builder()
                .url(base.newBuilder()
                        .addQueryParameter("appid", wechatProperties.getAppId())
                        .addQueryParameter("secret", wechatProperties.getAppSecret())
                        .addQueryParameter("js_code", code)
                        .addQueryParameter("grant_type", "authorization_code")
                        .build())
                .get()
                .build();

        String body;
        try (Response response = okHttpClient.newCall(request).execute()) {
            body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                log.error("微信 code2Session HTTP {}: {}", response.code(), truncate(body));
                throw new BusinessException(ErrorCode.ERROR, "微信登录服务暂时不可用，请稍后重试");
            }
        } catch (IOException e) {
            // BusinessException 是 RuntimeException，不会被这个 catch 吞掉
            log.error("微信 code2Session 网络异常: {}", e.getMessage());
            throw new BusinessException(ErrorCode.ERROR, "微信登录服务暂时不可用，请稍后重试");
        }

        JsonNode node = jsonUtil.readTree(body);
        String openid = node == null ? null : node.path("openid").asText(null);
        if (openid == null || openid.isBlank()) {
            // 最常见的是 errcode=40029 invalid code：code 只能用一次，
            // 小程序热重载后拿旧 code 重放就会撞上。让用户重新登录即可。
            log.warn("微信 code2Session 未返回 openid: errcode={}, errmsg={}",
                    node == null ? "非JSON响应" : node.path("errcode").asText("-"),
                    node == null ? truncate(body) : node.path("errmsg").asText(""));
            throw BusinessException.unauthorized("微信登录已失效，请重新登录");
        }
        return openid;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_LOG_BODY ? text : text.substring(0, MAX_LOG_BODY) + "...";
    }

    @Override
    public User getById(Long userId) {
        return userId == null ? null : userMapper.selectById(userId);
    }

    @Override
    public User findByOpenid(String openid) {
        return (openid == null || openid.isBlank()) ? null : userMapper.selectByOpenid(openid);
    }

    @Override
    public User findOrCreateByOpenid(String openid) {
        User existing = findByOpenid(openid);
        if (existing != null) {
            return existing;
        }

        User user = new User();
        user.setOpenid(openid);
        // nickname 留空：微信登录拿不到昵称（getUserProfile 已废弃），
        // 小程序侧用 config.business.defaultNickname「微信用户」兜底展示
        try {
            userMapper.insert(user);
            log.info("新学生首次登录，已建档: userId={}", user.getId());
            return user;
        } catch (DuplicateKeyException e) {
            // 同一个人同时点了两次登录：user.openid 上有 UNIQUE 索引，
            // 后到的那条插入会被拦下。重查一次拿回先到请求建好的记录即可，
            // 不能把异常抛出去 —— 那样用户会看到"服务器错误"，而其实他已经注册成功了。
            User raced = userMapper.selectByOpenid(openid);
            if (raced != null) {
                return raced;
            }
            throw e;
        }
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
