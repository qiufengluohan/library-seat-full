package com.example.libraryseat.security;

import com.example.libraryseat.common.BusinessException;
import com.example.libraryseat.util.JwtUtil;

/**
 * 当前请求的登录身份，由 {@link JwtInterceptor} 在 preHandle 里写入、
 * afterCompletion 里清除。
 *
 * <p><b>两个必须知道的限制：</b>
 *
 * <ol>
 *   <li><b>只在一个 HTTP 请求线程内有效。</b>{@code @Async} 方法和
 *       {@code @Scheduled} 定时任务跑在别的线程上，这里读到的是 null。
 *       所以所有异步 / 定时逻辑都必须把 userId 作为参数显式传进去，
 *       不要在异步方法体里调 {@link #userId()}。</li>
 *   <li><b>userId 的含义取决于 role。</b>role=ADMIN 时它是
 *       {@code admin_user.id}，role=STUDENT 时是 {@code user.id}。
 *       两张表的主键各自自增，数值可能撞车，
 *       写 operation_log 前必须先确认自己拿的是哪一个。</li>
 * </ol>
 */
public final class AuthContext {

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    /** 登录主体。userId + role 两个字段就够了，编码规范 §36 明确不做 RBAC。 */
    public record Principal(Long userId, String role) {

        public boolean isAdmin() {
            return JwtUtil.ROLE_ADMIN.equals(role);
        }

        public boolean isStudent() {
            return JwtUtil.ROLE_STUDENT.equals(role);
        }
    }

    public static void set(Long userId, String role) {
        HOLDER.set(new Principal(userId, role));
    }

    public static Principal get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 取当前登录用户 ID。
     *
     * @throws BusinessException 未登录时抛 401。理论上走不到这里 ——
     *         拦截器已经挡掉了无 token 的请求 —— 但 Controller 依赖它取值时
     *         不应该拿到 null 再往下传，那会变成"以游客身份预约"。
     */
    public static Long userId() {
        Principal principal = HOLDER.get();
        if (principal == null || principal.userId() == null) {
            throw BusinessException.unauthorized("登录已过期，请重新登录");
        }
        return principal.userId();
    }

    public static String role() {
        Principal principal = HOLDER.get();
        return principal == null ? null : principal.role();
    }

    public static boolean isAdmin() {
        Principal principal = HOLDER.get();
        return principal != null && principal.isAdmin();
    }

    /**
     * 管理员专用：取 admin_user.id 用于写 operation_log。
     *
     * @throws BusinessException 当前不是管理员身份时抛 403
     */
    public static Long adminId() {
        Principal principal = HOLDER.get();
        if (principal == null || !principal.isAdmin()) {
            throw BusinessException.forbidden("需要管理员权限");
        }
        return principal.userId();
    }
}
