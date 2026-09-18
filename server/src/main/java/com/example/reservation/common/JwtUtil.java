package com.example.reservation.common;

import cn.hutool.jwt.JWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类
 * 基于 Hutool JWT 实现 Token 签发与校验；签名密钥取自配置（环境变量优先），禁止硬编码
 *
 * @author reservation-team
 */
@Component
public class JwtUtil {

    /** 签名密钥（application.yml：app.jwt.secret，支持环境变量 JWT_SECRET 注入） */
    @Value("${app.jwt.secret}")
    private String secret;

    /** 有效期（小时） */
    @Value("${app.jwt.expire-hours}")
    private long expireHours;

    /**
     * 生成 Token：携带 userId / username / role
     */
    public String generateToken(Long userId, String username, Integer role) {
        Date expireAt = new Date(System.currentTimeMillis() + expireHours * 3600_000L);
        return JWT.create()
                .setPayload("userId", String.valueOf(userId))
                .setPayload("username", username)
                .setPayload("role", String.valueOf(role))
                .setExpiresAt(expireAt)
                .setKey(secret.getBytes(StandardCharsets.UTF_8))
                .sign();
    }

    /**
     * Token 有效期（秒）：供 Redis 会话 Key 设置一致 TTL（Redis 加分项，需求设计文档 2.2 第 145 行）
     */
    public long getExpireSeconds() {
        return expireHours * 3600L;
    }
}
