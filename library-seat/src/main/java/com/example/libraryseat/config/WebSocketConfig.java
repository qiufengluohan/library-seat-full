package com.example.libraryseat.config;

import com.example.libraryseat.websocket.SeatWebSocketHandler;
import com.example.libraryseat.websocket.TokenHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册 {@code /ws/seats}。编码规范 §18：所有客户端连同一个地址、收同一种消息。
 *
 * <p>路径必须和管理端 {@code utils/websocket.js} 里拼的一致，
 * 那边写死的是 {@code ${base}/ws/seats?token=xxx}。
 *
 * <p>{@code setAllowedOriginPatterns("*")} 而不是 {@code allowedOrigins("*")}：
 * 管理端开发时页面在 5173、后端在 8080，属于跨源握手，
 * Spring 只允许通配写法走 patterns 这个 API（两者同时用会启动报错）。
 * 生产环境应收窄成实际域名。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final SeatWebSocketHandler seatWebSocketHandler;
    private final TokenHandshakeInterceptor tokenHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(seatWebSocketHandler, "/ws/seats")
                .addInterceptors(tokenHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
