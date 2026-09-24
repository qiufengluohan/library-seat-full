package com.example.libraryseat.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全馆座位实时推送的唯一出口。编码规范 §18 定义的消息类型都从这里发出去
 * （具体有哪几种、为什么少一种，见 {@link WsMessage} 的类注释）。
 *
 * <p>分层上它属于 §18 的最后一环：
 * {@code Controller → Service → 业务处理 → MySQL → WebSocket}。
 * 也就是说<b>只有 Service 可以调 {@link #broadcast}</b>，
 * Controller 不要自己拼消息、更不要自己发。
 *
 * <h2>几个容易踩的点</h2>
 *
 * <ul>
 *   <li><b>为什么用 ConcurrentWebSocketSessionDecorator：</b>广播会同时来自
 *       HTTP 请求线程、{@code @Async} 的下发线程和 {@code @Scheduled} 的巡检线程。
 *       裸 WebSocketSession 不允许并发 send，撞上就是
 *       {@code IllegalStateException: TEXT_PARTIAL_WRITING}，
 *       而且这个 session 之后就一直废了。装饰器把并发写排队掉。</li>
 *   <li><b>为什么按 session.getId() 存：</b>{@code afterConnectionClosed}
 *       回调传进来的是<b>原始</b> session，不是我们存的装饰器，
 *       直接 {@code Set.remove(session)} 永远删不掉，会慢慢漏连接。</li>
 *   <li><b>广播失败不能影响业务：</b>推送只是"让界面早点更新"，
 *       数据库才是事实来源。某个客户端网络断了不该让预约事务回滚，
 *       所以每个 session 的异常都单独吞掉并记日志。</li>
 *   <li>服务端不主动发心跳：管理端断线后 3 秒重连，重连成功会重新拉一次
 *       全量列表（{@code stores/seat.js} 的 reconnected 分支），漏掉的消息由此补齐。</li>
 * </ul>
 */
@Slf4j
@Component
public class SeatWebSocketHandler extends TextWebSocketHandler {

    /** 单次 send 超过这个时长就认为对端卡死，关掉它，别拖累整轮广播 */
    private static final int SEND_TIME_LIMIT_MS = 5_000;

    /** 单个 session 积压超过 64KB 就关掉。正常情况下管理端消费很快，堆到这个量说明对端已经不读了 */
    private static final int BUFFER_SIZE_LIMIT = 64 * 1024;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public SeatWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(),
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT));
        log.info("WebSocket 已连接: id={}, 当前在线连接数={}", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket 已断开: id={}, code={}, 当前在线连接数={}",
                session.getId(), status.getCode(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 出错后这个 session 基本不可用了，主动摘掉，等客户端自己重连
        sessions.remove(session.getId());
        log.warn("WebSocket 传输错误，已移除连接 id={}: {}", session.getId(), exception.getMessage());
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException ignored) {
            // 已经在断了，关不掉也无所谓
        }
    }

    /**
     * 向所有已连接的管理端广播一条消息。
     *
     * <p>序列化只做一次，然后逐个 session 发送；单个失败不影响其余。
     *
     * @param message 用 {@link WsMessage} 的静态工厂构造，不要手写字段，
     *                否则会漏掉 {@code @JsonInclude(NON_NULL)} 想要的"不填就不发"语义
     */
    public void broadcast(WsMessage message) {
        if (message == null || message.getSeatId() == null) {
            // seat_id 缺失的消息到了前端会被 patchSeat 直接丢弃，发了也是白发
            log.warn("广播消息缺少 seat_id，已忽略: type={}",
                    message == null ? null : message.getType());
            return;
        }
        if (sessions.isEmpty()) {
            return;
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("广播消息序列化失败: {}", message, e);
            return;
        }

        TextMessage textMessage = new TextMessage(payload);
        for (Map.Entry<String, WebSocketSession> entry : sessions.entrySet()) {
            WebSocketSession session = entry.getValue();
            if (!session.isOpen()) {
                sessions.remove(entry.getKey());
                continue;
            }
            try {
                session.sendMessage(textMessage);
            } catch (Exception e) {
                sessions.remove(entry.getKey());
                log.warn("推送失败，已移除连接 id={}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    /** 当前连接数，用于 dashboard 自检和排查"前端说收不到推送" */
    public int connectionCount() {
        return sessions.size();
    }
}
