package com.example.libraryseat.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 编码规范 §12 建议的错误码。
 *
 * <p>这里的数字同时是 Result.code 的值。注意它和 HTTP 状态码不是一一对应的：
 * 只有 401 和 500 会真的作为 HTTP 状态返回，其余一律 HTTP 200 + 信封里的 code。
 * 原因见 {@link GlobalExceptionHandler}。
 */
@Getter
public enum ErrorCode {

    SUCCESS(200, "success"),
    BAD_REQUEST(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或登录已失效"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "数据不存在"),
    CONFLICT(409, "业务冲突"),
    ERROR(500, "服务器错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 这个错误码对应哪个真实 HTTP 状态。
     *
     * <p>放在枚举里而不是各处 switch，是为了让 {@link GlobalExceptionHandler} 和
     * {@code security.JwtInterceptor} 对同一种错误给出完全一致的响应 ——
     * 拦截器直接写 response，绕过了 @RestControllerAdvice，两边一旦各写一份
     * 映射，"未登录"就会出现两种不同的 HTTP 状态，前端只认其中一种。
     */
    public HttpStatus httpStatus() {
        return switch (this) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.OK;
        };
    }
}
