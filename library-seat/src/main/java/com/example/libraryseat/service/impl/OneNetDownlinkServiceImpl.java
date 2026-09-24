package com.example.libraryseat.service.impl;

import com.example.libraryseat.config.OneNetProperties;
import com.example.libraryseat.service.OneNetDownlinkService;
import com.example.libraryseat.util.JsonUtil;
import com.example.libraryseat.util.OneNetTokenUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class OneNetDownlinkServiceImpl implements OneNetDownlinkService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 平台返回体里的成功码。OneNET 用 0 表示成功，不是 HTTP 200 那一套 */
    private static final int PLATFORM_OK = 0;

    /** 日志里最多打这么长的返回体，防止平台回一大坨 HTML 把日志刷满 */
    private static final int MAX_LOG_BODY = 500;

    /** 下行消息 id，平台用它做幂等/追踪，自增就够 */
    private final AtomicLong messageId = new AtomicLong(0);

    private final OneNetProperties properties;
    private final OkHttpClient httpClient;
    private final JsonUtil jsonUtil;

    @PostConstruct
    void reportConfiguration() {
        if (isConfigured()) {
            log.info("OneNET 下行已就绪: baseUrl={}, productId={}",
                    properties.getBaseUrl(), properties.getProductId());
            return;
        }
        log.warn("""
                OneNET 下行未配置（onenet.product-id / onenet.api-key 为空）。\
                上行推送照常接收，但"修改阈值 / 测试蜂鸣器 / 显示恢复"都会跳过。\
                配置方式：设置环境变量 ONENET_API_KEY 后重启，api-key 不要写进任何提交到仓库的文件。""");
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Async
    @Override
    public void setProperties(String deviceName, Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", nextMessageId());
        body.put("version", "1.0");
        body.put("params", props);
        post(properties.getPropertySetPath(), deviceName, body, "属性下发 " + props.keySet());
    }

    @Async
    @Override
    public void invokeService(String deviceName, String serviceId, Map<String, Object> params) {
        if (serviceId == null || serviceId.isBlank()) {
            log.warn("服务调用缺少 service_id，已跳过: device={}", deviceName);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service_id", serviceId);
        // 物模型把 buzzer_ctrl 标成 sync，这里仍然按 async 发：
        // 设备离线时同步调用会挂到读超时，把管理端的请求线程一起拖住
        body.put("type", "async");
        if (params != null && !params.isEmpty()) {
            body.put("params", params);
        }
        post(properties.getServiceInvokePath(), deviceName, body, "服务调用 " + serviceId);
    }

    /**
     * 所有下行请求的唯一出口。<b>不抛异常</b>，理由见接口注释。
     *
     * <p>日志里绝对不打 {@code authorization} 头：那串 token 在有效期内
     * 等同于产品级 api-key 的使用权，泄露出去别人就能控制全部设备。
     */
    private void post(String path, String deviceName, Map<String, Object> body, String action) {
        if (deviceName == null || deviceName.isBlank()) {
            log.warn("{}缺少设备名，已跳过", action);
            return;
        }
        if (!isConfigured()) {
            log.warn("OneNET 凭证未配置，跳过{}: device={}", action, deviceName);
            return;
        }

        String url = properties.getBaseUrl() + path
                + "?product_id=" + encode(properties.getProductId())
                + "&device_name=" + encode(deviceName);
        String json = jsonUtil.toJson(body);
        if (json == null) {
            log.error("{}的请求体序列化失败，已跳过: device={}", action, deviceName);
            return;
        }

        Request request = new Request.Builder()
                .url(url)
                .header("authorization", buildToken())
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String text = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                log.error("{}失败: device={}, http={}, 返回={}",
                        action, deviceName, response.code(), truncate(text));
                return;
            }
            Integer code = platformCode(text);
            if (code != null && code != PLATFORM_OK) {
                log.error("{}被平台拒绝: device={}, code={}, 返回={}",
                        action, deviceName, code, truncate(text));
                return;
            }
            log.info("{}已下发: device={}, 请求体={}", action, deviceName, json);
        } catch (Exception e) {
            // 超时、DNS、连接重置都在这里。设备离线是最常见的原因，不该刷 ERROR 栈
            log.warn("{}未送达: device={}, 原因={}", action, deviceName, e.toString());
        }
    }

    private String buildToken() {
        long expireAt = Instant.now().getEpochSecond() + properties.getTokenExpireSeconds();
        return OneNetTokenUtil.buildToken(properties.getTokenVersion(), properties.tokenRes(),
                properties.getTokenMethod(), properties.getApiKey(), expireAt);
    }

    private Integer platformCode(String text) {
        JsonNode root = jsonUtil.readTree(text);
        if (root == null) {
            // 返回体不是 JSON（网关挂了会回 HTML）。HTTP 200 + 非 JSON 视为成功送达，
            // 设备到底收没收到看下一次上报的值
            return null;
        }
        JsonNode code = root.get("code");
        return code != null && code.canConvertToInt() ? code.asInt() : null;
    }

    private String nextMessageId() {
        return Instant.now().getEpochSecond() + "-" + messageId.incrementAndGet();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_LOG_BODY ? text : text.substring(0, MAX_LOG_BODY) + "...";
    }
}
