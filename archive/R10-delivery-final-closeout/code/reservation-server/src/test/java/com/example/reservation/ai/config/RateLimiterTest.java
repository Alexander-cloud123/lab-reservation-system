package com.example.reservation.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 限流器（N2 修复）纯单测：不开启 AI、不消耗额度，注入可控时钟推进窗口。
 * 覆盖：用户维度超限、全站维度超限、窗口推进恢复、淘汰策略修正（超上限时其他用户窗口不被清空）。
 *
 * @author reservation-team
 */
class RateLimiterTest {

    /** 可控时钟（测试推进窗口用） */
    private static final class MutableClock implements RateLimiter.Clock {
        private long now = 0;

        @Override
        public long currentTimeMillis() {
            return now;
        }
    }

    @Test
    @DisplayName("N2：用户维度第 rpmLimit+1 次被拒（上限=2，同用户第 3 次拒，其他用户不受影响）")
    void userLimit_exceeded_thirdCallRejected() {
        RateLimiter limiter = new RateLimiter(2, 100, 60_000, new MutableClock());
        assertTrue(limiter.tryAcquire(1L), "第 1 次应放行");
        assertTrue(limiter.tryAcquire(1L), "第 2 次应放行（达到上限）");
        assertFalse(limiter.tryAcquire(1L), "第 3 次应被拒（超过用户上限）");
        assertTrue(limiter.tryAcquire(2L), "其他用户不受影响");
    }

    @Test
    @DisplayName("N2：全站维度累计达 global-rpm-limit 后被拒（上限=1，第二次即拒，即使不同用户）")
    void globalLimit_exceeded_secondCallRejected() {
        RateLimiter limiter = new RateLimiter(100, 1, 60_000, new MutableClock());
        assertTrue(limiter.tryAcquire(1L), "第 1 次应放行");
        assertFalse(limiter.tryAcquire(2L), "全站第 2 次应被拒（即使不同用户）");
    }

    @Test
    @DisplayName("N2：窗口推进后可恢复（注入时钟推进 60s，用户/全站计数重置）")
    void window_rollsOver_afterWindowMs() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(1, 1, 60_000, clock);
        assertTrue(limiter.tryAcquire(1L), "窗口内第 1 次放行");
        assertFalse(limiter.tryAcquire(1L), "用户维度超限（第 2 次）");
        clock.now += 60_000;
        assertTrue(limiter.tryAcquire(1L), "窗口推进后用户维度恢复");
        assertFalse(limiter.tryAcquire(2L), "窗口推进后全站维度：第 2 次仍被拒（全站计数也在同一窗口）");
        clock.now += 60_000;
        assertTrue(limiter.tryAcquire(2L), "再次推进后全站维度恢复");
    }

    @Test
    @DisplayName("N2：超 MAX_TRACKED_USERS 时仅淘汰最早窗口，其他用户窗口不被清空（修正 clear() 全清的绕过面）")
    void trim_removesOldestOnly_otherWindowsKeepCounts() {
        MutableClock clock = new MutableClock();
        // maxTracked=1：跟踪窗口数超过 1 即触发淘汰
        RateLimiter limiter = new RateLimiter(2, 100, 60_000, clock, 1);
        clock.now = 1_000;
        assertTrue(limiter.tryAcquire(1L), "A 第 1 次放行");
        assertTrue(limiter.tryAcquire(1L), "A 第 2 次放行（达用户上限）");
        clock.now = 2_000;
        assertTrue(limiter.tryAcquire(2L), "B 第 1 次放行；此时跟踪数=2>1 → 触发淘汰（最早者 A）");
        assertTrue(limiter.tryAcquire(2L), "B 第 2 次放行");
        // 修正点证据：B 的窗口未被清空——若被 clear() 全清，B 的计数会归零，第 3 次反而放行
        assertFalse(limiter.tryAcquire(2L), "B 第 3 次仍被拒（B 窗口计数保留，未被全清重置）");
        // 淘汰只删最旧一个窗口：被淘汰者重建后可正常重新计数（而不是所有窗口一起重置）
        assertTrue(limiter.tryAcquire(1L), "被淘汰窗口重建后第 1 次放行（定向淘汰未影响其他窗口计数）");
    }
}
