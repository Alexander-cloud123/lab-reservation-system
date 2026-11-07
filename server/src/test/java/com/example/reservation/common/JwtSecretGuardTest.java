package com.example.reservation.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 非开发环境 JWT 密钥启动门禁测试。 */
class JwtSecretGuardTest {

    @Test
    @DisplayName("生产 profile 拒绝公开默认密钥和短密钥")
    void validate_rejectsWeakProductionSecrets() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class,
                () -> new JwtSecretGuard(environment, "reservation-system-jwt-secret-2026").validate());
        assertThrows(IllegalStateException.class,
                () -> new JwtSecretGuard(environment, "short").validate());
        assertThrows(IllegalStateException.class,
                () -> new JwtSecretGuard(environment, "").validate());
    }

    @Test
    @DisplayName("生产 profile 接受独立强密钥，本机无 profile 保持开发便利")
    void validate_acceptsStrongSecretAndLocalDefault() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        assertDoesNotThrow(() -> new JwtSecretGuard(production, "0123456789abcdef0123456789abcdef").validate());

        MockEnvironment local = new MockEnvironment();
        assertDoesNotThrow(() -> new JwtSecretGuard(local, "reservation-system-jwt-secret-2026").validate());
    }
}
