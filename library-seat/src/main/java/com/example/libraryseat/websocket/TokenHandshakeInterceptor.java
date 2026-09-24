package com.example.libraryseat.websocket;

import com.example.libraryseat.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * {@code /ws/seats} 的握手鉴权。
 *
 * <p>浏览器原生 WebSocket <b>不能自定义请求头</b>，所以管理端
 * {@code utils/websocket.js} 把 token 放在查询参数里：
 * {@code /ws/seats?token=xxx}。这里负责在<b>握手阶段</b>就校验它。
 *
 * <h2>为什么在握手期拒绝，而不是先连上再 close</h2>
 *
 * 连上再关会先触发一次 open，管理端 {@code stores/seat.js} 会把
 * wsConnected 置 true，界面短暂显示"实时连接正常"，紧接着才断开重连，
 * 状态灯会闪。握手期直接返回 403，客户端连 open 都收不到，行为更干净。
 *
 * <p>路径不在 {@code /api/**} 之下，{@code JwtInterceptor} 覆盖不到，
 * 这是 /ws/seats 唯一的鉴权点，删掉它等于把全馆实时状态开放给任何人。
 *
 * <p>角色不限：编码规范 §18 说"所有客户端都接收同一种消息"，
 * 而座位状态本来就通过 {@code GET /api/seats} 对学生开放（§14.2），
 * 推给学生的信息不比 REST 更多。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "userId";
    public static final String ATTR_ROLE = "role";

    private final JwtUtil jwtUtil;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {

        String token = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("token");

        Claims claims = jwtUtil.parse(token);
        Long userId = claims == null ? null : JwtUtil.userIdOf(claims);

        if (userId == null) {
            log.debug("WebSocket 握手被拒绝：token 无效或缺失");
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_ROLE, JwtUtil.roleOf(claims));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 无需处理
    }
}
