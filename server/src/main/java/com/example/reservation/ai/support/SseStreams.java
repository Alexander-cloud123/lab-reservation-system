package com.example.reservation.ai.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * AI SSE 端点生命周期工具（R12 沉淀 / R13-5 收敛）。
 *
 * <p>统一 4 个 SSE 端点（chat + recommend/parse/compliance）完全同构的样板，
 * 行为与 R12 chat 端点逐行对齐，禁止在 Controller 复制第四份：
 * <ul>
 *   <li>超时（从请求建立即起算）：置 finished → 尽力补发 error 帧 → complete；</li>
 *   <li>连接错误/客户端断开：置 finished（业务层 sink 写失败后静默收尾）；</li>
 *   <li>任务在 aiStreamExecutor 执行，排队期间已超时/出错则跳过全部业务与上游调用；</li>
 *   <li>线程池/队列打满（请求线程拒绝）：发 error 帧后 complete；</li>
 *   <li>业务异常：连接已死仅 debug；否则 warn 并补发 error 帧；finally 保证 complete 恰好一次。</li>
 * </ul>
 */
@Slf4j
public final class SseStreams {

    private SseStreams() {
    }

    /** 流式业务任务（允许抛出受控/非受控异常，由本工具统一兜底） */
    @FunctionalInterface
    public interface SseTask<S> {
        void run(S sink) throws Exception;
    }

    /**
     * 建立 SSE 连接并提交流式任务。
     *
     * @param timeoutMs       连接超时（ms），须略大于上游读超时，避免容器先于上游断开
     * @param executor        AI 流式专用线程池
     * @param label           业务名（仅日志定位，如「流式问答」「流式推荐」）
     * @param userId          请求线程捕获的登录用户 ID（异步线程无 UserContext ThreadLocal）
     * @param sinkFactory     emitter → 业务 sink 的构造器（在请求线程、注册回调前创建，超时/拒绝时即可发 error）
     * @param task            流式业务（service 编排事件序列）
     * @param timeoutMessage  超时 error 帧文案
     * @param rejectedMessage 线程池拒绝 error 帧文案
     * @param errorMessage    业务执行异常 error 帧文案
     * @return 已注册回调并提交任务的 emitter（Controller 直接返回）
     */
    public static <S extends AiStreamSink> SseEmitter start(
            long timeoutMs, ThreadPoolTaskExecutor executor, String label, Long userId,
            Function<SseEmitter, S> sinkFactory, SseTask<S> task,
            String timeoutMessage, String rejectedMessage, String errorMessage) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        S sink = sinkFactory.apply(emitter);
        final AtomicBoolean finished = new AtomicBoolean(false);

        emitter.onTimeout(() -> {
            finished.set(true);
            // 超时收尾：尽力补发 error 帧，避免前端永久停留在等待态；连接可能已被容器回收，发送失败吞掉
            safeSendError(sink, timeoutMessage);
            tryComplete(emitter);
        });
        emitter.onError(t -> {
            finished.set(true);
            log.debug("SSE 连接异常/客户端断开（{}）：userId={}，{}", label, userId, t.toString());
        });

        try {
            executor.execute(() -> {
                // 排队期间已超时/出错：连接已死，跳过全部业务与上游调用，避免白耗模型配额
                if (finished.get()) {
                    return;
                }
                try {
                    task.run(sink);
                } catch (Exception e) {
                    if (finished.get()) {
                        log.debug("{}在连接结束后异常：userId={}，{}", label, userId, e.toString());
                        return;
                    }
                    log.warn("{}执行异常：userId={}", label, userId, e);
                    safeSendError(sink, errorMessage);
                } finally {
                    if (!finished.get()) {
                        tryComplete(emitter);
                    }
                }
            });
        } catch (TaskRejectedException e) {
            // 线程池/队列打满：连接刚建立尚未发业务事件，直接发 error 事件收尾
            log.warn("{}线程池已满，拒绝本次请求：userId={}", label, userId);
            safeSendError(sink, rejectedMessage);
            tryComplete(emitter);
        }
        return emitter;
    }

    /** error 事件发送兜底（忽略二次失败，如客户端已断开） */
    private static void safeSendError(AiStreamSink sink, String message) {
        try {
            sink.error(message);
        } catch (Exception ignored) {
            // 连接已关闭，无任何可做的补救
        }
    }

    /** complete 兜底（极端时序下二次收尾可能失败，忽略） */
    private static void tryComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignore) {
            // 忽略二次收尾异常
        }
    }
}
