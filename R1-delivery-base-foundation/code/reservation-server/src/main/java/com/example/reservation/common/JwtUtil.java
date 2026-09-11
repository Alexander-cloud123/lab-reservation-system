package com.example.reservation.common;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

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
     * 校验并解析 Token，返回载荷；非法/过期抛业务异常(401)
     */
    public Map<String, Object> parseToken(String token) {
        try {
            JWT jwt = JWTUtil.parseToken(token);
            if (!jwt.setKey(secret.getBytes(StandardCharsets.UTF_8)).verify()) {
                throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
            }
            return jwt.getPayloads();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
        }
    }
}
