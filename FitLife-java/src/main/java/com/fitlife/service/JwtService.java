package com.fitlife.service;

import com.fitlife.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验。
 *
 * <p>P1 改动：
 * <ul>
 *   <li>secret 不再写死在 application.yml，改由环境变量 {@code JWT_SECRET} 注入；</li>
 *   <li>启动时校验 secret 非空且长度 ≥32 字节（HS256 的最低强度），
 *       配置错误在启动阶段就暴露，而不是等到第一个用户登录；</li>
 *   <li>token 里增加 {@code role} 声明，供过滤器装载 ROLE_ADMIN / ROLE_USER。</li>
 * </ul>
 */
@Service
public class JwtService {

    /** HS256 要求密钥至少 32 字节 */
    private static final int MIN_SECRET_BYTES = 32;

    @Value("${jwt.secret:}")
    private String secret;

    @Value("${jwt.expiration:604800000}")
    private Long expiration;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret 未配置：请设置环境变量 JWT_SECRET（≥32 字符），或使用 local profile 的默认开发密钥");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 长度不足：当前 " + keyBytes.length + " 字节，HS256 至少需要 " + MIN_SECRET_BYTES + " 字节");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(User user) {
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim("role", user.getRole() == null ? User.ROLE_USER : user.getRole())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(secretKey)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return extractClaims(token).getSubject();
    }

    public Long extractUserId(String token) {
        return extractClaims(token).get("userId", Long.class);
    }

    public String extractRole(String token) {
        return extractClaims(token).get("role", String.class);
    }

    public SecretKey getSecretKey() {
        return secretKey;
    }
}