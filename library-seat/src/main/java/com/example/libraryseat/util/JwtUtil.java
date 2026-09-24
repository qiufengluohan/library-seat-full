package com.example.libraryseat.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 签发与校验，编码规范 §36。
 *
 * <p>claims 里只放 userId / role / 过期时间。role 只有 STUDENT 和 ADMIN 两个值，
 * 用来区分"能不能访问 /api/admin/*"（§37），<b>不做 RBAC</b>，不要往里加权限树。
 */
@Slf4j
@Component
public class JwtUtil {

    public static final String ROLE_STUDENT = "STUDENT";
    public static final String ROLE_ADMIN = "ADMIN";

    public static final String CLAIM_USER_ID = "userId";
    public static final String CLAIM_ROLE = "role";

    private final Key key;
    private final long expireMillis;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expire-minutes}") long expireMinutes) {
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            // HS256 要求密钥至少 256 位。这里启动即失败，
            // 好过运行到第一个登录请求才抛 WeakKeyException。
            throw new IllegalStateException(
                    "jwt.secret 至少需要 32 字节，当前 " + secretBytes.length
                            + " 字节。请设置环境变量 JWT_SECRET。");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expireMillis = expireMinutes * 60_000L;
    }

    public String generate(Long userId, String role) {
        Date now = new Date();
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_USER_ID, userId);
        claims.put(CLAIM_ROLE, role);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(String.valueOf(userId))
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + expireMillis))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 校验并解析 token。
     *
     * @return 无效 / 过期 / 签名不符时返回 null。调用方据此统一回 401，
     *         不需要区分具体原因（也不该把原因告诉客户端）。
     */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            log.debug("token 校验失败: {}", e.getMessage());
            return null;
        }
    }

    /** 从 Authorization 头里取出裸 token，兼容缺失 Bearer 前缀的写法。 */
    public static String stripBearer(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String trimmed = header.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    public static Long userIdOf(Claims claims) {
        Object value = claims.get(CLAIM_USER_ID);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String roleOf(Claims claims) {
        Object value = claims.get(CLAIM_ROLE);
        return value == null ? null : value.toString();
    }
}
