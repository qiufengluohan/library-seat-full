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
     * OneNET 下行与微信 code2Session 共用一个客户端，连接池共享。
     *
     * <p>超时取 OneNET 的配置（更严格的一方）。微信接口偶尔慢，
     * 5 秒读超时对登录场景也够用；真嫌慢再拆开。
     */
    @Bean
    public OkHttpClient okHttpClient(OneNetProperties oneNetProperties) {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(oneNetProperties.getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(oneNetProperties.getReadTimeoutMs()))
                .build();
    }
}
