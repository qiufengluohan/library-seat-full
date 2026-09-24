package com.example.libraryseat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * OneNET Studio 接入配置。
 *
 * <p>方案 §53「Web 想直接调用 OneNET：禁止」、编码规范 §42「前端绝对不能直接拼
 * OneNET 请求」—— 因此 product-id / api-key 只存在于后端进程的环境变量里，
 * 前端仓库中不应出现任何 OneNET 凭证。
 *
 * <h2>为什么路径也要做成配置</h2>
 *
 * Studio 的开放 API 路径随平台版本调整过，而本项目无法在开发期直接连通
 * iot-api.heclouds.com 验证。所以<b>每一个下行路径都留成配置项</b>：
 * 真机联调时如果收到 404，改 application.yml 一行即可，不用改 Java、不用重新编译。
 * 上线前请对照 OneNET 控制台「应用开发 → API 文档」核对一遍下面的默认值。
 *
 * <p>当前默认值对应的是 <b>Studio（企业实例）</b>，域名 iot-api.heclouds.com。
 * 旧的 open.iot.10086.cn 是" OneNET 标准版/旧版"，接口和鉴权都不同，不要混用。
 * 判定依据：本项目 product_id 是 10 位字母数字混合（{@code Wh08f3Q71p}），
 * 物模型里带 {@code profile.industryId/sceneId/categoryId}，这两个都是 Studio 特征。
 */
@Data
@Component
@ConfigurationProperties(prefix = "onenet")
public class OneNetProperties {

    private String baseUrl = "https://iot-api.heclouds.com";

    /** 产品 ID，来自 OneNET 控制台产品详情页 */
    private String productId = "";

    /**
     * 产品级 API Key（base64 串）。签名时要先 base64 解码得到真正的 HMAC 密钥，
     * 这一步很容易漏 —— 见 {@code util.OneNetTokenUtil}。
     */
    private String apiKey = "";

    /* ------------------------------------------------------------
     * token 参数（Studio 的 authorization 头）
     * ------------------------------------------------------------ */

    private String tokenVersion = "2022-05-01";

    /** 签名算法，Studio 支持 sha1 / sha256 / md5，默认 sha1 */
    private String tokenMethod = "sha1";

    /** 资源前缀。产品级 key 用 {@code products/}，设备级 key 要改成 {@code products/<pid>/devices/<name>} */
    private String tokenResPrefix = "products/";

    /** token 有效期（秒）。默认 1 小时，客户端每次请求现算，不做缓存 */
    private long tokenExpireSeconds = 3600;

    /* ------------------------------------------------------------
     * 下行 API 路径 —— 联调若 404 只改这里
     * ------------------------------------------------------------ */

    /** 属性下发：改 adc_threshold / seat_display */
    private String propertySetPath = "/iot-api/device/property/set";

    /** 服务调用：buzzer_ctrl / force_release */
    private String serviceInvokePath = "/iot-api/device/service/invoke";

    /* ------------------------------------------------------------
     * 上行推送校验
     * ------------------------------------------------------------ */

    /**
     * 上行推送的共享密钥。{@code /api/onenet/**} 必须免 JWT（OneNET 不会带我们的 token），
     * 少了这道校验任何人都能伪造"设备上报"把座位状态刷成任意值。
     *
     * <p>留空表示不校验 —— 开发期方便，<b>部署到公网前必须设置</b>，
     * 未设置时启动会打 WARN。请求侧用 {@code X-Push-Secret} 头或
     * {@code ?push_secret=} 查询参数携带，两种都认，因为 OneNET 的
     * HTTP 推送配置不一定允许自定义头。
     */
    private String pushSecret = "";

    /* ------------------------------------------------------------
     * HTTP 客户端
     * ------------------------------------------------------------ */

    private int connectTimeoutMs = 3000;

    private int readTimeoutMs = 5000;

    /**
     * 强制释放时要下发的 seat_display 值（编码规范 §14.9 第 7 步"发送显示恢复指令"）。
     * 物模型里 seat_display 是 0-255 的 int32，具体哪个数字代表"恢复正常显示"
     * 由 STM32 固件定义，这里默认 0，按固件实际约定改。
     */
    private int displayClearValue = 0;

    public boolean isConfigured() {
        return notBlank(productId) && notBlank(apiKey);
    }

    /** token 里的 res 字段，签名和拼 URL 都要用同一个值 */
    public String tokenRes() {
        return tokenResPrefix + productId;
    }

    public boolean isPushSecretConfigured() {
        return notBlank(pushSecret);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
