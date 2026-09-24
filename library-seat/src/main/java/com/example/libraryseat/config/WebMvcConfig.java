package com.example.libraryseat.config;

import com.example.libraryseat.security.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 层配置：JWT 拦截器 + 跨域。
 *
 * <h2>免鉴权路径</h2>
 *
 * <ul>
 *   <li>{@code /api/auth/wechat-login}、{@code /api/admin/login} —— 登录本身当然不能要求已登录。</li>
 * </ul>
 *
 * <p>{@code /api/onenet/**} <b>不在</b>免鉴权名单里：它照样进
 * {@link JwtInterceptor}，只是在里面走另一条分支 —— 用
 * {@link OneNetProperties#getPushSecret()} 共享密钥校验，而不是 JWT
 * （OneNET 的 HTTP 推送不可能带我们的 token）。
 *
 * <p>{@code /ws/seats} 不在 {@code /api/**} 之下，拦截器管不到它，
 * 它的鉴权在 {@code websocket.TokenHandshakeInterceptor} 里做。
 *
 * <h2>跨域</h2>
 *
 * 管理端开发时走 Vite 代理，其实是同源的，并不依赖 CORS。
 * 这里放开是为了另一种常见用法：把 {@code VITE_API_BASE_URL} 直接填成
 * {@code http://localhost:8080} 而不走代理。用 allowedOriginPatterns("*")
 * 而不是 allowedOrigins("*")，因为 Spring 只允许前者与通配头组合使用。
 *
 * <p>鉴权走 Authorization 头而不是 Cookie，所以不需要 allowCredentials，
 * 也就没有"允许任意源 + 携带凭证"这个安全坑。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/wechat-login",
                        "/api/admin/login");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
