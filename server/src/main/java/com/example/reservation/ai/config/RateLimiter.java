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
 * 淘汰策略（N2 修正）：跟踪窗口数超 {@code maxTracked} 时，先清理窗口已过期的条目；
 * 仍超上限再按「最早过期者」淘汰单个条目——禁止 clear() 全清（全清等于把所有人的额度一起重置，是被绕过的口子）。
 *
 * @author reservation-team
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

    /**
     * 淘汰策略（N2 修正）：跟踪窗口数超上限时，先清理窗口已过期的条目；
     * 仍超上限再按「最早过期者」淘汰单个条目（禁止 clear() 全清，避免全站额度一起被重置）。
     */
    private void trim(long now) {
        if (userWindows.size() <= maxTracked) {
            return;
        }
        // 1) 先删窗口已过期的条目（固定窗口结束即失去计数意义）
        userWindows.entrySet().removeIf(e -> now - e.getValue().windowStart >= windowMs);
        if (userWindows.size() <= maxTracked) {
            return;
        }
        // 2) 仍超上限：淘汰最早过期者（windowStart 最小者）
        Map.Entry<Long, WindowCounter> oldest = null;
        for (Map.Entry<Long, WindowCounter> e : userWindows.entrySet()) {
            if (oldest == null || e.getValue().windowStart < oldest.getValue().windowStart) {
                oldest = e;
            }
        }
        if (oldest != null) {
            // remove(key, value)：仅在值仍为该窗口时删除，避免误删并发下已重建的窗口
            userWindows.remove(oldest.getKey(), oldest.getValue());
        }
    }
}
