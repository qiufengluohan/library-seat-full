package com.example.libraryseat.service.impl;

import com.example.libraryseat.config.OneNetProperties;
import com.example.libraryseat.service.OneNetDownlinkService;
import com.example.libraryseat.util.JsonUtil;
import com.example.libraryseat.util.OneNetTokenUtil;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OneNetDownlinkServiceImpl implements OneNetDownlinkService {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /**
     * 数字型返回码那一套的成功值；字符串 code + success 布尔那一套见 platformRejection
     */
    private static final int PLATFORM_OK = 0;

    /**
     * 日志里最多打这么长的返回体，防止平台回一大坨 HTML 把日志刷满
     */
    private static final int MAX_LOG_BODY = 500;

    private final OneNetProperties properties;
    private final OkHttpClient httpClient;
    private final JsonUtil jsonUtil;

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_LOG_BODY ? text : text.substring(0, MAX_LOG_BODY) + "...";
    }

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
        /* OneNET v5 的 set-device-property 只认三个字段：
               {"product_id":..., "device_name":..., "params":{...}}
           id / version 由平台自己生成，多发反而可能被严格校验拒掉。
           product_id / device_name 统一由 post() 注入，这里只负责 params。 */
        Map<String, Object> body = new LinkedHashMap<>();
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
        /* OneNET v5 的 call-service 字段是：
               {"product_id","device_name","identifier","params"}
           ⚠️ 是 **identifier**，不是 service_id；也没有 type 字段
           （同步/异步由物模型里该服务自己的 callType 决定，本产品的 buzzer_ctrl 就是 async）。 */
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identifier", serviceId);
        /* 🔴 params 是平台的必填字段，**不能省**：
           2026-11-05 实测少了这个键会回 10001
           "Key: 'DeviceFuseProperty.Params' ... failed on the 'required' tag"。
           即便服务本身没有入参，也要发一个空对象。 */
        body.put("params", params == null ? new LinkedHashMap<String, Object>() : params);
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

        /* 🔴 OneNET v5 的物模型接口把 product_id / device_name 放在 **JSON body** 里，
           不是 URL query —— 放 query 会回 10001 Field validation failed。
           另外 baseUrl 已经含域名，path 里不能再带 /iot-api 前缀，
           否则网关直接回 406 "HTTP url namespace err"（2026-10-19 实际踩到）。 */
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("product_id", properties.getProductId());
        payload.put("device_name", deviceName);
        payload.putAll(body);

        String url = properties.getBaseUrl() + path;
        String json = jsonUtil.toJson(payload);
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
            String rejection = platformRejection(text);
            if (rejection != null) {
                log.error("{}被平台拒绝: device={}, {}, 返回={}",
                        action, deviceName, rejection, truncate(text));
                return;
            }
            // 把平台返回体一起打出来：成功时是 {"code":0,"msg":"succ"}，
            // 联调时这一行能直接证明"平台真的接单了"，而不是我们自己认为成功了
            log.info("{}已下发: device={}, 平台返回={}, 请求体={}",
                    action, deviceName, truncate(text), json);
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

    /**
     * 平台返回体里的拒绝原因，{@code null} 表示没有拒绝。
     *
     * <p>要同时认两套：旧接口用数字 {@code code}（0 为成功），
     * {@code application} 命名空间用字符串 {@code code}（如
     * {@code iot.application.authPermissionDeny}）加 {@code success:false}。
     * 只认数字那套的话，鉴权被拒会走 HTTP 200 + 非数字 code，
     * 被记成「已下发」——管理端显示成功而平台根本没接，比直接失败更糟。
     */
    String platformRejection(String text) {
        JsonNode root = jsonUtil.readTree(text);
        if (root == null) {
            // 返回体不是 JSON（网关挂了会回 HTML）。HTTP 200 + 非 JSON 视为成功送达，
            // 设备到底收没收到看下一次上报的值
            return null;
        }
        // 🔴 2026-10-29 修：v5 成功时返回 {"code":0,"msg":"succ"}，**没有 success 字段**。
        //    原来直接 root.get("success").isBoolean() ⇒ 每次成功都抛 NPE，
        //    被 post() 的 catch 记成「未送达」，管理端以为下发失败，实际平台已经接单了。
        //    所以这里每个字段都必须先判空。
        JsonNode success = root.get("success");
        if (success != null && success.isBoolean() && !success.asBoolean()) {
            return "code=" + root.path("code").asText("-") + ", msg=" + root.path("msg").asText("-");
        }
        JsonNode code = root.get("code");
        if (code == null || code.isNull()) {
            return null;
        }
        if (code.canConvertToInt()) {
            return code.asInt() != PLATFORM_OK ? "code=" + code.asInt()
                    + ", msg=" + root.path("msg").asText("-") : null;
        }
        if (code.isTextual() && !code.asText().isBlank() && !"0".equals(code.asText())) {
            return "code=" + code.asText();
        }
        return null;
    }
}
