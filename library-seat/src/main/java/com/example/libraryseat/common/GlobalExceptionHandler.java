package com.example.libraryseat.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理，把异常统一转成 Result 信封（编码规范 §12）。
 *
 * <h2>HTTP 状态码怎么给</h2>
 *
 * 这里的取舍是被两个前端的现有代码倒逼出来的，不要随手改：
 *
 * <ul>
 *   <li><b>401 必须是真实 HTTP 401。</b>小程序 {@code utils/request.js} 只在
 *       {@code statusCode === 401} 时清 token 并跳登录页，信封里的 code:401 不会触发；
 *       Web 管理端的 axios 错误拦截器同样只看 HTTP 状态。</li>
 *   <li><b>500 必须是真实 HTTP 500。</b>管理端靠"500 且 body 里没有 message"来区分
 *       Vite 代理连不上后端和后端真的炸了，返回 HTTP 200 会让这个判断失效。</li>
 *   <li><b>400 / 403 / 404 / 409 一律 HTTP 200 + 信封 code。</b>管理端把 409 当作
 *       正常业务反馈，用 {@code error.silent = body.code === 409} 抑制弹窗，
 *       这段逻辑在 axios 的<b>成功</b>拦截器里；一旦返回真实 HTTP 409，
 *       走的会是错误拦截器，冲突提示就会变成红色报错弹窗。</li>
 * </ul>
 *
 * <p>映射本身写在 {@link ErrorCode#httpStatus()} 里，JWT 拦截器共用同一份。
 * 要改就改那里，别在这儿再写一个 switch。
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        return respond(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<Result<Void>> handleValidation(BindException e) {
        FieldError first = e.getBindingResult().getFieldError();
        String message = first == null
                ? ErrorCode.BAD_REQUEST.getMessage()
                : wireName(first.getField()) + " " + first.getDefaultMessage();
        return respond(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 校验注解挂在 Java 字段上，Spring 报回来的是 camelCase 的 {@code seatId}；
     * 但前端发的、以及规范 §4 约定的都是 snake_case 的 {@code seat_id}。
     * 把 camelCase 名字回给用户，等于他明明发对了字段却被告知发错了。
     *
     * <p>过一遍 ObjectMapper 上<b>实际生效</b>的命名策略，而不是自己写正则拆驼峰：
     * 将来谁改了 {@code spring.jackson.property-naming-strategy}，这句提示会跟着变，
     * 不会和响应体的字段名脱节。
     */
    private String wireName(String javaField) {
        PropertyNamingStrategy strategy = objectMapper.getPropertyNamingStrategy();
        return strategy instanceof PropertyNamingStrategies.NamingBase base
                ? base.translate(javaField)
                : javaField;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        // 请求体为空或不是合法 JSON。小程序对无参 POST 会发 {}，不会走到这里。
        return respond(ErrorCode.BAD_REQUEST, "请求体格式错误");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return respond(ErrorCode.BAD_REQUEST, "缺少参数 " + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(ErrorCode.BAD_REQUEST, "参数 " + e.getName() + " 类型错误");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        return respond(ErrorCode.NOT_FOUND, "请求的资源不存在");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknown(Exception e) {
        log.error("未捕获异常", e);
        return respond(ErrorCode.ERROR, ErrorCode.ERROR.getMessage());
    }

    private ResponseEntity<Result<Void>> respond(ErrorCode errorCode, String message) {
        String text = (message == null || message.isBlank()) ? errorCode.getMessage() : message;
        return ResponseEntity.status(errorCode.httpStatus()).body(Result.of(errorCode, text));
    }
}
