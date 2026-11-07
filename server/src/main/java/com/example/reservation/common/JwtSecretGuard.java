package com.example.reservation.common;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 非本地开发环境的 JWT 密钥启动检查。
 * 默认密钥只用于无显式 profile 的本机运行；dev/local/test 之外的环境必须注入独立强密钥。
 *
 * @author hyr
 */
@Component
public class JwtSecretGuard {

    private static final String DEVELOPMENT_DEFAULT = "reservation-system-jwt-secret-2026";
    private static final int MIN_SECRET_BYTES = 32;

    private final Environment environment;
    private final String secret;

    public JwtSecretGuard(Environment environment, @Value("${app.jwt.secret}") String secret) {
        this.environment = environment;
        this.secret = secret;
    }

    /** 弱密钥或缺密钥时阻止非开发环境启动，避免公开默认值被误用于部署。 */
    @PostConstruct
    public void validate() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length == 0 || isDevelopmentProfile(activeProfiles)) {
            return;
        }
        if (secret == null || secret.isBlank()
                || DEVELOPMENT_DEFAULT.equals(secret)
                || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "非开发环境必须通过 JWT_SECRET 注入至少 32 字节的独立密钥，且不能使用开发默认值");
        }
    }

    private boolean isDevelopmentProfile(String[] activeProfiles) {
        return Arrays.stream(activeProfiles)
                .allMatch(profile -> profile.equalsIgnoreCase("dev")
                        || profile.equalsIgnoreCase("local")
                        || profile.equalsIgnoreCase("test"));
    }
}
