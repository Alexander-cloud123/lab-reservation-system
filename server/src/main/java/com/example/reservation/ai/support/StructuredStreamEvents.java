package com.example.reservation.ai.support;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 结构化流式事件编排公共助手（R13-5）：收敛三个结构化 service 完全相同的
 * 「关闭态短路 / 进度回调 / result→done 收尾」写法，不复制第四份。
 *
 * <p>中断语义与流式问答（R12）一致：sink 写失败（客户端断开）抛 IOException，
 * 在阶段回调路径包装为 {@link UncheckedIOException} 向上中止——
 * 由 {@code AiInvoker} 透传到执行线程，跳过上游模型调用，不空耗配额。
 */
@Slf4j
public final class StructuredStreamEvents {

    private StructuredStreamEvents() {
    }

    /**
     * AI 关闭态短路：meta(false,true,message) → result(enabled=false 的关闭 VO) → done。
     * 写失败静默（客户端已断开）。
     */
    public static <T> void emitDisabled(StructuredStreamSink<T> sink, T disabledVo, String message) {
        try {
            sink.meta(false, true, message);
            sink.result(disabledVo);
            sink.done();
        } catch (IOException e) {
            log.debug("结构化流客户端已断开（关闭分支）：{}", e.getMessage());
        }
    }

    /**
     * 成功/降级收尾：result（同构 VO）→ done。写失败静默。
     */
    public static <T> void finish(StructuredStreamSink<T> sink, T vo) {
        try {
            sink.result(vo);
            sink.done();
        } catch (IOException e) {
            log.debug("结构化流收尾失败（客户端已断开）：{}", e.getMessage());
        }
    }

    /**
     * 构造「调用上游前」阶段回调：写 phase 帧；客户端已断开时抛 UncheckedIOException
     * 阻止后续上游调用（作为 AiInvoker 的 beforeUpstreamCall 入参）。
     */
    public static Runnable phaseEmitter(StructuredStreamSink<?> sink, String phase, String text) {
        return () -> {
            try {
                sink.phase(phase, text);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
