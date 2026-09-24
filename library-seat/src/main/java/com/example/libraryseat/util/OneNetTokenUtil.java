package com.example.libraryseat.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * OneNET Studio 的 {@code authorization} 头生成。
 *
 * <p>Studio（企业实例，域名 iot-api.heclouds.com）的 token 形如：
 * <pre>
 * version=2022-05-01&amp;res=products/{product_id}&amp;et={过期秒}&amp;method=sha1&amp;signature={签名}
 * </pre>
 * 待签串是四段用 {@code \n} 连接的<b>固定顺序</b>：{@code et\nmethod\nres\nversion}。
 * 顺序记错是最常见的坑，现象是平台一律返回 401 且不告诉你哪段错了。
 *
 * <p>HMAC 的密钥不是 api-key 原文，而是它 <b>base64 解码后的字节</b> ——
 * 这一步漏掉的话签名永远不对，而且看起来"格式是对的"，极难排查。
 *
 * <p>签名结果本身是 base64，可能含 {@code + / =}。放在 HTTP 头里不需要转义，
 * 但如果哪天要把它拼进 URL 查询参数，必须整体 URLEncode，否则 {@code +} 会被解成空格。
 */
public final class OneNetTokenUtil {

    private OneNetTokenUtil() {
    }

    /**
     * @param version  固定 {@code 2022-05-01}，见 {@code onenet.token-version}
     * @param res      资源串，产品级 key 是 {@code products/{product_id}}
     * @param method   sha1 / sha256 / md5
     * @param apiKey   控制台上的 api-key（base64 串）
     * @param expireAt token 过期时刻，epoch <b>秒</b>
     */
    public static String buildToken(String version, String res, String method,
                                    String apiKey, long expireAt) {
        return "version=" + version
                + "&res=" + res
                + "&et=" + expireAt
                + "&method=" + method
                + "&signature=" + sign(apiKey, expireAt, method, res, version);
    }

    public static String sign(String apiKey, long expireAt, String method, String res, String version) {
        byte[] key = decodeKey(apiKey);
        String stringToSign = expireAt + "\n" + method + "\n" + res + "\n" + version;
        try {
            Mac mac = Mac.getInstance(hmacAlgorithm(method));
            mac.init(new SecretKeySpec(key, mac.getAlgorithm()));
            byte[] raw = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            // method 来自配置文件，写错就是配置错误，让它炸出来比默默返回空签名好
            throw new IllegalStateException("生成 OneNET token 失败（method=" + method + "）: " + e.getMessage(), e);
        }
    }

    private static byte[] decodeKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("onenet.api-key 未配置，无法调用 OneNET 下行接口");
        }
        try {
            return Base64.getDecoder().decode(apiKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("onenet.api-key 不是合法的 base64 串，请核对控制台上的产品级 key", e);
        }
    }

    private static String hmacAlgorithm(String method) {
        String normalized = method == null ? "" : method.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "sha1" -> "HmacSHA1";
            case "sha256" -> "HmacSHA256";
            case "md5" -> "HmacMD5";
            default -> throw new IllegalStateException("不支持的签名算法: " + method
                    + "（OneNET Studio 只认 sha1 / sha256 / md5）");
        };
    }
}
