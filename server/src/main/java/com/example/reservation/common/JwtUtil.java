package com.example.reservation.common;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.signers.JWTSignerUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类
 * 基于 Hutool JWT 实现 Token 签发与校验；签名密钥取自配置（环境变量优先），禁止硬编码
 *
 * @author hyr
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
     * 生成 Token：携带 userId / username / role / tokenVersion
     *
     * <p>tokenVersion 取自 sys_user.token_version，是服务端持久撤销的凭据：
     * 登出 / 改密 / 管理员重置 / 禁用会让库中版本号自增，旧 Token 即便签名与 exp 都合法，
     * 也会因版本不匹配被拦截器拒绝（Redis 关闭、重启、多实例下同样生效）。</p>
     *
     * @param tokenVersion 当前 Token 版本号（null 视为 0，兼容历史数据）
     */
    public String generateToken(Long userId, String username, Integer role, Integer tokenVersion) {
        Date expireAt = new Date(System.currentTimeMillis() + expireHours * 3600_000L);
        return JWT.create()
                .setPayload("userId", String.valueOf(userId))
                .setPayload("username", username)
                .setPayload("role", String.valueOf(role))
                .setPayload("tokenVersion", String.valueOf(tokenVersion == null ? 0 : tokenVersion))
                .setExpiresAt(expireAt)
                // 签发与 AuthInterceptor 验证统一固定 HS256，不使用 Token 头部选择算法。
                .setSigner(JWTSignerUtil.hs256(secret.getBytes(StandardCharsets.UTF_8)))
                .sign();
    }

    /**
     * Token 有效期（秒）：供 Redis 会话 Key 设置一致 TTL（Redis 加分项，需求设计文档 2.2 第 145 行）
     */
    public long getExpireSeconds() {
        return expireHours * 3600L;
    }
}
