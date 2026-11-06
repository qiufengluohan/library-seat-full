package com.example.libraryseat.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

/**
 * 跨模块共用的基础设施 Bean。
 *
 * <p>放一起是因为这两个 Bean 都只有一个实现、没有可替换的必要，
 * 各自单开一个配置类只会增加文件数。
 */
@Configuration
public class AppConfig {

    /**
     * 编码规范 §35：密码必须使用 BCrypt。
     *
     * <p>只引 spring-security-crypto，不引 spring-boot-starter-security ——
     * 本项目用 JwtInterceptor 自己做鉴权，引入完整 Security 会带来
     * 默认表单登录、CSRF、以及和 CORS 配置打架的一堆自动配置。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * OneNET 下行用的 HTTP 客户端（设备属性下发 / 服务调用）。
     *
     * <p>超时取 OneNET 的配置。密码校验走 spring-security-crypto 的
     * BCryptPasswordEncoder —— 那是纯本地计算，不联网，用不上这个客户端。
     */
    @Bean
    public OkHttpClient okHttpClient(OneNetProperties oneNetProperties) {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(oneNetProperties.getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(oneNetProperties.getReadTimeoutMs()))
                .build();
    }
}
