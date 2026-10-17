package com.example.reservation.ai.support;

import com.example.reservation.ai.dto.AiStreamError;
import com.example.reservation.ai.dto.AiStreamMeta;
import com.example.reservation.ai.dto.AiStreamPhase;
import com.example.reservation.ai.dto.AiStreamResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * {@link StructuredStreamSink} 的 SSE 实现（R13-5）：每个方法对应一个 SSE 命名事件。
 *
 * <p>payload 以对象传入，由 Spring 用 Jackson 序列化为 JSON（UTF-8），中文不转义；
 * {@code result} 事件经 {@link AiStreamResult} 的 {@code @JsonValue} 展平，
 * 线上 data 即 VO 本身，与非流式接口 {@code data} 同构。
 * 客户端断开 / emitter 已完成时 send 抛异常，统一转 IOException 交 service 提前收尾。
 */
@Slf4j
public class SseStructuredStreamSink<T> implements StructuredStreamSink<T> {

    private final SseEmitter emitter;

    public SseStructuredStreamSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void meta(boolean enabled, boolean degraded, String message) throws IOException {
        send("meta", new AiStreamMeta(enabled, degraded, message));
    }

    @Override
    public void phase(String phase, String text) throws IOException {
        send("phase", new AiStreamPhase(phase, text));
    }

    @Override
    public void result(T vo) throws IOException {
        send("result", AiStreamResult.of(vo));
    }

    @Override
    public void done() throws IOException {
        // done 无业务载荷，发空对象（前端不读字段，仅作终态信号）
        send("done", new Object());
    }

    @Override
    public void error(String message) throws IOException {
        send("error", new AiStreamError(message));
    }

    /** 发送单个命名事件；IllegalStateException（emitter 已完成）转 IOException */
    private void send(String eventName, Object payload) throws IOException {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IllegalStateException e) {
            log.debug("SSE 发送失败：event={}，原因={}", eventName, e.getMessage());
            throw new IOException("SSE sink closed: " + eventName, e);
        }
    }
}
