package com.example.libraryseat.security;

import com.example.libraryseat.common.ErrorCode;
import com.example.libraryseat.common.Result;
import com.example.libraryseat.config.OneNetProperties;
import com.example.libraryseat.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 接口鉴权拦截器：学生/管理员走 JWT，OneNET 推送走共享密钥。
 * 编码规范 §36 / §37：只区分 STUDENT 和 ADMIN 两种角色，不做 RBAC。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li>免鉴权路径在 {@code config.WebMvcConfig} 里 exclude：微信登录、管理员登录。</li>
 *   <li>{@code /api/onenet/**} 不校验 JWT —— OneNET 的推送不可能带我们的 token。
 *       它改用 {@code onenet.push-secret} 共享密钥校验，见 {@link #checkPushSecret}。
 *       之所以放在这个拦截器里而不是 Controller 里，是为了让<b>所有</b>鉴权判定
 *       都能在同一个文件里审计到。</li>
 *   <li>{@code /api/admin/**} 必须 role=ADMIN，否则 403。</li>
 *   <li>其余 {@code /api/**} 只要 token 有效即可（学生端）。</li>
 * </ul>
 *
 * <h2>为什么自己写 response 而不是抛异常</h2>
 *
 * 拦截器抛出的异常不一定会被 {@code @RestControllerAdvice} 接住
 * （取决于抛出时机是否在 DispatcherServlet 的 handler 调用链内），
 * 落到容器默认错误页就变成一段 HTML，前端的 axios 解析不出 Result 结构。
 * 所以这里直接写 JSON，并且<b>复用容器里的 ObjectMapper</b> ——
 * 它是配了 SNAKE_CASE 的那个，保证 401 响应体和正常响应体字段风格一致。
 *
 * <p>HTTP 状态由 {@link ErrorCode#httpStatus()} 决定，和全局异常处理完全一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private static final String ADMIN_PATH_PREFIX = "/api/admin/";

    private static final String ONENET_PATH_PREFIX = "/api/onenet/";

    /** 推送密钥的请求头名，OneNET 数据推送支持自定义 HTTP 头。 */
    private static final String PUSH_SECRET_HEADER = "X-Push-Secret";

    /** 推送密钥的查询参数名，给只能配 URL、配不了请求头的推送通道用。 */
    private static final String PUSH_SECRET_PARAM = "push_secret";

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final OneNetProperties oneNetProperties;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {

        // CORS 预检不带 Authorization，放行给 CorsConfig 处理
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        // OneNET 推送走共享密钥，不走 JWT。必须在下面取 Authorization 之前分流，
        // 否则平台推来的每一条数据都会被打成"登录已过期"。
        if (request.getRequestURI().startsWith(ONENET_PATH_PREFIX)) {
            return checkPushSecret(request, response);
        }

        String token = JwtUtil.stripBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        Claims claims = jwtUtil.parse(token);

        if (claims == null) {
            // token 缺失 / 过期 / 签名不符，一律同一句话。
            // 不把原因告诉客户端，避免变成账号探测的信息源。
            writeError(response, ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
            return false;
        }

        Long userId = JwtUtil.userIdOf(claims);
        String role = JwtUtil.roleOf(claims);

        if (userId == null || role == null) {
            log.warn("token 合法但缺少 userId/role claim，可能是旧版本签发的");
            writeError(response, ErrorCode.UNAUTHORIZED, "登录状态无效，请重新登录");
            return false;
        }

        if (request.getRequestURI().startsWith(ADMIN_PATH_PREFIX)
                && !JwtUtil.ROLE_ADMIN.equals(role)) {
            writeError(response, ErrorCode.FORBIDDEN, "需要管理员权限");
            return false;
        }

        // 所有校验都通过后才写 ThreadLocal：中途 return false 的路径不会留下脏身份，
        // 而 Spring 对返回 false 的拦截器不会回调 afterCompletion。
        AuthContext.set(userId, role);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        // Tomcat 复用线程，不清就会把上一个请求的身份带给下一个请求
        AuthContext.clear();
    }

    /**
     * 校验 OneNET 上行推送的共享密钥。
     *
     * <p>没配 {@code onenet.push-secret} 时<b>放行</b>：本地开发用 Postman 打
     * {@code /api/onenet/device-data} 是编码规范 §46 的第 4、5 步，
     * 那时候还没配密钥，一律 401 会让人以为接口坏了。代价是这个端点在未配置时
     * 是公开的，任何人都能伪造一条设备上报 —— 所以每次放行都记一条 warn，
     * 上线前把密钥配上，warn 消失即证明配好了。
     *
     * <p>用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：
     * equals 在第一个不同字节处就返回，攻击者可以靠响应时间逐位猜密钥。
     * 这个接口在公网可达，值得花这一行。
     */
    private boolean checkPushSecret(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        String expected = oneNetProperties.getPushSecret();
        if (expected == null || expected.isBlank()) {
            log.warn("未配置 onenet.push-secret，{} 处于免鉴权状态，任何人都能伪造设备上报",
                    request.getRequestURI());
            return true;
        }

        String actual = request.getHeader(PUSH_SECRET_HEADER);
        if (actual == null || actual.isBlank()) {
            actual = request.getParameter(PUSH_SECRET_PARAM);
        }
        // OneNET 追加签名参数时拼的是 "?"，push_secret 的值会被粘上一截 nonce
        if (actual != null) {
            int cut = actual.indexOf('?');
            if (cut > 0) {
                actual = actual.substring(0, cut);
            }
        }
        // 日志里只记"有没有带"，不记值：推送密钥等同于伪造设备数据的权限
        if (actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            log.warn("OneNET 推送密钥校验失败: uri={}, 是否携带密钥={}, 期望长度={}, 实收长度={}, 实收是否带?={}",
                                     request.getRequestURI(), actual != null, expected.length(),
                                      actual == null ? -1 : actual.length(),
                                       actual != null && actual.indexOf('?') >= 0);
            writeError(response, ErrorCode.UNAUTHORIZED, "推送密钥校验失败");
            return false;
        }
        return true;
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode, String message)
            throws IOException {
        response.setStatus(errorCode.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(Result.of(errorCode, message)));
    }
}
