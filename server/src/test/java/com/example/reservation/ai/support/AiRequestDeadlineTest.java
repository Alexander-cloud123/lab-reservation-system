package com.example.reservation.ai.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AI 请求绝对超时的纯 JDK 回归测试。 */
class AiRequestDeadlineTest {

    @Test
    @DisplayName("无作用域时所有配置仍封顶 60 秒")
    void remainingOrDefault_capsGlobalTimeout() {
        assertEquals(Duration.ofSeconds(60),
                AiRequestDeadline.remainingOrDefault(Duration.ofSeconds(120)));
    }

    @Test
    @DisplayName("作用域只提供剩余预算，close 后恢复，过期预算不再允许上游等待")
    void scopeUsesRemainingBudgetAndRestoresThreadLocal() {
        AiRequestDeadline deadline = AiRequestDeadline.of(Duration.ofMillis(50));
        try (AiRequestDeadline.Scope ignored = deadline.open()) {
            long remaining = AiRequestDeadline.remainingOrDefault(Duration.ofSeconds(60)).toMillis();
            assertTrue(remaining <= 50);
            assertFalse(AiRequestDeadline.isCurrentExpired());
        }
        assertEquals(Duration.ofSeconds(60),
                AiRequestDeadline.remainingOrDefault(Duration.ofSeconds(60)));

        AiRequestDeadline expired = AiRequestDeadline.of(Duration.ZERO);
        try (AiRequestDeadline.Scope ignored = expired.open()) {
            assertTrue(AiRequestDeadline.isCurrentExpired());
            assertEquals(Duration.ZERO, AiRequestDeadline.remainingOrDefault(Duration.ofSeconds(60)));
            assertEquals(1L, expired.emitterTimeoutMillis());
        }
        assertFalse(AiRequestDeadline.isCurrentExpired());
    }
}
