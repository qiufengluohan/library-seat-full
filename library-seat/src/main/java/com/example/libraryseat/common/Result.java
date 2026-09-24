package com.example.libraryseat.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一返回体，编码规范 §12。
 *
 * <p>字段名 code / message / data 不含下划线，所以 Jackson 的 SNAKE_CASE 策略
 * 对它是无操作 —— 小程序和 Web 管理端解析的都是这三个名字。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(ErrorCode.ERROR.getCode(), message, null);
    }

    public static <T> Result<T> of(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 用自定义文案覆盖枚举里的默认文案，例如 "座位已被预约"。 */
    public static <T> Result<T> of(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }
}
