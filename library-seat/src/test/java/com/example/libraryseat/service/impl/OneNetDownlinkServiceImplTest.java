package com.example.libraryseat.service.impl;

import com.example.libraryseat.config.OneNetProperties;
import com.example.libraryseat.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 下行返回体判读测试。
 *
 * <p>钉住 2026-10-29 那个 NPE：v5 成功时返回 {@code {"code":0,"msg":"succ"}}，
 * <b>没有 success 字段</b>。原来实现直接 {@code root.get("success").isBoolean()}
 * ⇒ 每次成功都抛 NPE，被上层 catch 记成「未送达」，管理端误以为下发失败。
 *
 * <p>这里把四种真实返回体都钉死：成功不能被判成失败，失败也不能被判成成功。
 */
class OneNetDownlinkServiceImplTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OneNetDownlinkServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OneNetDownlinkServiceImpl(new OneNetProperties(),
                new OkHttpClient(), new JsonUtil(MAPPER));
    }

    @Test
    @DisplayName("v5 成功返回 {code:0,msg:succ}：没有 success 字段也不能抛异常、不能判失败")
    void successWithoutSuccessFieldIsNotRejection() {
        assertNull(service.platformRejection("""
                {"code":0,"msg":"succ"}
                """));
    }

    @Test
    @DisplayName("v5 业务失败 code≠0：要判成拒绝，并带出 code 和 msg")
    void numericFailureIsRejection() {
        String reason = service.platformRejection("""
                {"code":10001,"msg":"Field validation failed"}
                """);
        assertEquals("code=10001, msg=Field validation failed", reason);
    }

    @Test
    @DisplayName("application 命名空间：success=false + 字符串 code")
    void applicationStyleRejection() {
        String reason = service.platformRejection("""
                {"success":false,"code":"iot.application.authPermissionDeny","msg":"denied"}
                """);
        assertEquals("code=iot.application.authPermissionDeny, msg=denied", reason);
    }

    @Test
    @DisplayName("返回体不是 JSON（网关回 HTML）时按成功处理，不刷 ERROR")
    void nonJsonIsTreatedAsSuccess() {
        assertNull(service.platformRejection("<html>502 Bad Gateway</html>"));
        assertNull(service.platformRejection(""));
    }

    @Test
    @DisplayName("只有 success=true 没有 code 也算成功")
    void onlySuccessTrue() {
        assertNull(service.platformRejection("""
                {"success":true}
                """));
    }
}
