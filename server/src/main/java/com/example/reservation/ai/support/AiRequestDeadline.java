package com.example.reservation.ai.support;

import java.time.Duration;

/**
 * 单次 AI 请求的绝对 deadline（单调时钟）。
 *
 * Controller 在请求入口创建，同步调用直接打开作用域，SSE 异步任务显式传播同一实例。
 * 上游帧超时、阻塞等待均只能使用剩余预算，不能在帧间或桥接阶段重置/追加 60 秒。
 *
 * @author hyr
 */
public final class AiRequestDeadline {

    /** AGENTS 4.4：AI 调用端到端不得超过 60 秒 */
    public static final long MAX_TIMEOUT_MS = 60_000L;
    public static final Duration MAX_TIMEOUT = Duration.ofMillis(MAX_TIMEOUT_MS);

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final ThreadLocal<AiRequestDeadline> CURRENT = new ThreadLocal<>();

    private final long deadlineNanos;

    private AiRequestDeadline(long timeoutNanos) {
        this.deadlineNanos = System.nanoTime() + Math.max(0L, timeoutNanos);
    }

    /** 从当前请求入口开始计算 60 秒绝对预算 */
    public static AiRequestDeadline start() {
        return new AiRequestDeadline(MAX_TIMEOUT.toNanos());
    }

    /** 测试/兼容入口：创建不超过 60 秒的预算 */
    public static AiRequestDeadline of(Duration timeout) {
        Duration capped = cap(timeout == null ? MAX_TIMEOUT : timeout);
        return new AiRequestDeadline(capped.toNanos());
    }

    /**
     * 当前作用域的剩余预算与调用方配置取较小值；无作用域时仍全局封顶 60 秒。
     */
    public static Duration remainingOrDefault(Duration configured) {
        Duration capped = cap(configured == null ? MAX_TIMEOUT : configured);
        AiRequestDeadline current = CURRENT.get();
        if (current == null) {
            return capped;
        }
        Duration remaining = current.remaining();
        return remaining.compareTo(capped) < 0 ? remaining : capped;
    }

    public static boolean isCurrentExpired() {
        AiRequestDeadline current = CURRENT.get();
        return current != null && current.isExpired();
    }

    public Duration remaining() {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return remainingNanos <= 0 ? Duration.ZERO : Duration.ofNanos(remainingNanos);
    }

    /** 毫秒向上取整，避免剩余不足 1ms 时被提前视为零 */
    public long remainingMillis() {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return 0L;
        }
        return Math.min(MAX_TIMEOUT_MS, (remainingNanos + NANOS_PER_MILLI - 1) / NANOS_PER_MILLI);
    }

    /** SseEmitter 不接受非正超时，过期请求用 1ms 让容器立即走 timeout 收尾 */
    public long emitterTimeoutMillis() {
        return Math.max(1L, remainingMillis());
    }

    public boolean isExpired() {
        return deadlineNanos - System.nanoTime() <= 0;
    }

    /** 将本 deadline 绑定到当前线程；close 恢复原作用域，防止线程池串用 */
    public Scope open() {
        AiRequestDeadline previous = CURRENT.get();
        CURRENT.set(this);
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {
        private final AiRequestDeadline previous;
        private boolean closed;

        private Scope(AiRequestDeadline previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    private static Duration cap(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            return Duration.ZERO;
        }
        return timeout.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : timeout;
    }
}
