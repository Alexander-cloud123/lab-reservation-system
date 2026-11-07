package com.example.reservation.ai.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 限流器（N2 修复：纯 JDK、无 Spring 依赖，时钟可注入便于纯单测，不开启 AI、不消耗额度）。
 * 双层固定窗口限流（窗口长度可配，默认 60s）：
 *  1. 全站维度：窗口内全站累计最多 {@code globalLimit} 次（N2 新增——防多用户合力打满全站额度）；
 *  2. 用户维度：窗口内每用户最多 {@code perUserLimit} 次（保持既有 M7 口径：rpm-limit/分钟/用户）。
 * 判定顺序：先查用户窗口是否已超限（超限直接拒，不消耗全站额度）；再扣全站额度；最后扣用户额度。
 * 淘汰策略：跟踪窗口数超 {@code maxTracked} 时只清理已过期条目；
 * 活动窗口即使超限数量也暂时保留，不能通过淘汰活动计数绕过用户额度。全局限额约束了单窗口新增条目增速。
 *
 * @author hyr
 */
public class RateLimiter {

    /** 跟踪用户窗口数兜底上限（与既有 MAX_TRACKED_USERS 口径一致） */
    private static final int DEFAULT_MAX_TRACKED = 10_000;

    /** 时钟抽象（可注入固定时钟推进窗口，便于纯单测） */
    public interface Clock {
        long currentTimeMillis();
    }

    /** 单窗口载体：固定窗口计数 + 窗口起点（由时钟统一驱动） */
    private static final class WindowCounter {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart;

        private WindowCounter(long now) {
            this.windowStart = now;
        }
    }

    private final int perUserLimit;
    private final int globalLimit;
    private final long windowMs;
    private final Clock clock;
    private final int maxTracked;

    /** 按用户维度的限流窗口（key=userId；null 用户退化为共享窗口键 -1L，保持 M7 语义） */
    private final Map<Long, WindowCounter> userWindows = new ConcurrentHashMap<>();
    /** 全站维度固定窗口（N2 新增） */
    private final WindowCounter globalWindow;

    public RateLimiter(int perUserLimit, int globalLimit, long windowMs) {
        this(perUserLimit, globalLimit, windowMs, System::currentTimeMillis, DEFAULT_MAX_TRACKED);
    }

    public RateLimiter(int perUserLimit, int globalLimit, long windowMs, Clock clock) {
        this(perUserLimit, globalLimit, windowMs, clock, DEFAULT_MAX_TRACKED);
    }

    public RateLimiter(int perUserLimit, int globalLimit, long windowMs, Clock clock, int maxTracked) {
        this.perUserLimit = Math.max(perUserLimit, 1);
        this.globalLimit = Math.max(globalLimit, 1);
        this.windowMs = windowMs;
        this.clock = clock;
        this.maxTracked = maxTracked;
        this.globalWindow = new WindowCounter(clock.currentTimeMillis());
    }

    /**
     * 尝试获取一次调用额度。
     *
     * @param userId 当前用户 ID；null 时退化为共享窗口（未登录场景理论不可达，AI 接口登录即可）
     * @return true=放行；false=触发限流（用户维度或全站维度任一超限，调用方直接返回友好失败，不触发对外调用）
     */
    public boolean tryAcquire(Long userId) {
        long now = clock.currentTimeMillis();
        Long key = userId == null ? -1L : userId;
        WindowCounter wc = userWindows.computeIfAbsent(key, k -> new WindowCounter(now));
        // 用户窗口与全站窗口在同一临界区内完成「检查 + 计数」，避免并发下窗口滑动与计数错乱
        synchronized (wc) {
            if (now - wc.windowStart >= windowMs) {
                wc.windowStart = now;
                wc.count.set(0);
            }
            // 1. 用户维度已超限：直接拒，不消耗全站额度
            if (wc.count.get() >= perUserLimit) {
                return false;
            }
            // 2. 全站维度（N2）：窗口滑动 + 超限检查 + 计数
            synchronized (globalWindow) {
                if (now - globalWindow.windowStart >= windowMs) {
                    globalWindow.windowStart = now;
                    globalWindow.count.set(0);
                }
                if (globalWindow.count.get() >= globalLimit) {
                    return false;
                }
                globalWindow.count.incrementAndGet();
            }
            wc.count.incrementAndGet();
            trim(now);
            return true;
        }
    }

    /** 只淘汰过期窗口；活动窗口保留计数，超限数量在全局限额约束下暂时存在并于后续窗口回收。 */
    private void trim(long now) {
        if (userWindows.size() <= maxTracked) {
            return;
        }
        userWindows.entrySet().removeIf(e -> now - e.getValue().windowStart >= windowMs);
        // 不得继续淘汰活动窗口：删除计数等于给该用户重新发放额度，会形成限流绕过。
    }
}
