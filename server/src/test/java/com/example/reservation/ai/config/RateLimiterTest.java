package com.example.reservation.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 限流器（N2 修复）纯单测：不开启 AI、不消耗额度，注入可控时钟推进窗口。
 * 覆盖：用户维度超限、全站维度超限、窗口推进恢复、活动窗口计数不被淘汰重置。
 *
 * @author hyr
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
    @DisplayName("活动窗口超过跟踪上限也不淘汰计数，不能借新用户重置旧用户额度")
    void trim_neverEvictsActiveWindowCounts() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(2, 100, 60_000, clock, 1);
        clock.now = 1_000;
        assertTrue(limiter.tryAcquire(1L));
        assertTrue(limiter.tryAcquire(1L), "A 达用户上限");
        clock.now = 2_000;
        assertTrue(limiter.tryAcquire(2L), "B 新窗口使跟踪数超过 maxTracked");
        assertTrue(limiter.tryAcquire(2L), "B 达用户上限");

        assertFalse(limiter.tryAcquire(2L), "B 的活动计数必须保留");
        assertFalse(limiter.tryAcquire(1L), "A 也不能因被淘汰而重新获得额度");

        clock.now += 60_000;
        assertTrue(limiter.tryAcquire(1L), "真实窗口到期后才允许重新计数并清理旧条目");
    }
}
