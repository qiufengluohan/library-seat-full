package com.example.libraryseat.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JSON 工具。
 *
 * <p>刻意做成 Spring 组件、注入容器里那个 ObjectMapper，而不是自己 new 一个静态的。
 * 容器的 mapper 带着 {@code SNAKE_CASE} 命名策略，WebSocketService 广播时必须复用它，
 * 否则发出去的是 {@code seatId}，小程序和 Web 管理端会同时解析失败
 * （library-seat/README.md 的"不要改的配置"讲的就是这件事）。
 * 自己 new 一个 mapper 是最容易踩的坑，所以这里不留静态入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonUtil {

    private final ObjectMapper objectMapper;

    /** 直接拿到容器的 mapper，广播等场景需要它本身而不是包装方法。 */
    public ObjectMapper mapper() {
        return objectMapper;
    }

    /**
     * 解析成 JsonNode。OneNET 推送的报文结构不完全可控，
     * 用树模型逐字段取比直接反序列化成 DTO 更抗变化。
     *
     * @return 解析失败返回 null，不抛异常 —— 一条坏报文不该把推送端点打成 500
     */
    public JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public JsonNode readTree(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return objectMapper.readTree(bytes);
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 序列化失败返回 null，调用方（主要是广播）自行降级，不影响主业务。 */
    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /** 把 JsonNode 的子树映射成 DTO，命名策略与容器一致。 */
    public <T> T convert(JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, type);
        } catch (Exception e) {
            log.warn("JSON 转换 {} 失败: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }
}
