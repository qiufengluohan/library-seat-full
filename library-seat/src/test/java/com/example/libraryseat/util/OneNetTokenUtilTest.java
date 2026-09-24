package com.example.libraryseat.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OneNET token 签名测试。
 *
 * <p>这里的期望值是<b>用 node 的 crypto 独立算出来的</b>，不是把实现抄一遍再断言相等 ——
 * 抄一遍只能证明代码没变，不能证明代码是对的。签名算法写错的后果是平台一律返回 401
 * 且不说明哪一段错了，所以这个 golden vector 是整个下行链路最值钱的一个断言。
 *
 * <pre>
 * node -e "crypto.createHmac('sha1', Buffer.from('dGVzdC1hcGkta2V5LWJhc2U2NA==','base64'))
 *          .update('1735689600\nsha1\nproducts/Wh08f3Q71p\n2022-05-01','utf8').digest('base64')"
 * → f+3jMu+nNxwgTs9ByUUOjhflNPM=
 * </pre>
 */
class OneNetTokenUtilTest {

    /** base64("test-api-key-base64")。假 key，不是真凭证。 */
    private static final String API_KEY = "dGVzdC1hcGkta2V5LWJhc2U2NA==";

    private static final long EXPIRE_AT = 1735689600L;
    private static final String RES = "products/Wh08f3Q71p";
    private static final String VERSION = "2022-05-01";
    private static final String EXPECTED_SHA1 = "f+3jMu+nNxwgTs9ByUUOjhflNPM=";

    @Test
    @DisplayName("sha1 签名与独立实现的 golden vector 一致")
    void signMatchesGoldenVector() {
        assertEquals(EXPECTED_SHA1, OneNetTokenUtil.sign(API_KEY, EXPIRE_AT, "sha1", RES, VERSION));
    }

    @Test
    @DisplayName("token 五段的顺序和分隔符固定")
    void buildTokenKeepsFieldOrder() {
        assertEquals("version=" + VERSION
                        + "&res=" + RES
                        + "&et=" + EXPIRE_AT
                        + "&method=sha1"
                        + "&signature=" + EXPECTED_SHA1,
                OneNetTokenUtil.buildToken(VERSION, RES, "sha1", API_KEY, EXPIRE_AT));
    }

    @Test
    @DisplayName("待签串四段顺序不可颠倒：换一段就换签名")
    void signatureDependsOnEverySegment() {
        String base = OneNetTokenUtil.sign(API_KEY, EXPIRE_AT, "sha1", RES, VERSION);
        // 这四个断言各自只改一段。任意一段参与签名的方式写错（比如顺序反了、
        // 少了 \n、用了 : 而不是 \n），对应的断言就会失败。
        assertNotEquals(base, OneNetTokenUtil.sign(API_KEY, EXPIRE_AT + 1, "sha1", RES, VERSION));
        assertNotEquals(base, OneNetTokenUtil.sign(API_KEY, EXPIRE_AT, "sha1", RES + "x", VERSION));
        assertNotEquals(base, OneNetTokenUtil.sign(API_KEY, EXPIRE_AT, "sha1", RES, VERSION + "x"));
        assertNotEquals(base, OneNetTokenUtil.sign(API_KEY, EXPIRE_AT, "sha256", RES, VERSION));
    }

    @Test
    @DisplayName("sha256 也走通，且结果长度对得上 32 字节摘要")
    void sha256IsSupported() {
        String signature = OneNetTokenUtil.sign(API_KEY, EXPIRE_AT, "sha256", RES, VERSION);
        assertNotEquals(EXPECTED_SHA1, signature);
        assertEquals(32, Base64.getDecoder().decode(signature).length);
    }

    @Test
    @DisplayName("api-key 未配置时炸出来，而不是发一个空签名给平台")
    void rejectsMissingApiKey() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> OneNetTokenUtil.sign("", EXPIRE_AT, "sha1", RES, VERSION));
        assertTrue(e.getMessage().contains("api-key"), e.getMessage());
    }

    @Test
    @DisplayName("api-key 不是合法 base64 时给出可读的原因")
    void rejectsMalformedApiKey() {
        assertThrows(IllegalStateException.class,
                () -> OneNetTokenUtil.sign("这不是base64!!", EXPIRE_AT, "sha1", RES, VERSION));
    }

    @Test
    @DisplayName("不支持的签名算法直接失败，不静默降级")
    void rejectsUnknownMethod() {
        assertThrows(IllegalStateException.class,
                () -> OneNetTokenUtil.sign(API_KEY, EXPIRE_AT, "sha512", RES, VERSION));
    }
}
