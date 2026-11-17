package com.example.reservation.ai.support;

import com.example.reservation.ai.dto.AiPlanVO;
import com.example.reservation.ai.dto.AiStreamDelta;
import com.example.reservation.ai.dto.AiStreamDone;
import com.example.reservation.ai.dto.AiStreamError;
import com.example.reservation.ai.dto.AiStreamMeta;
import com.example.reservation.ai.dto.AiStreamReasoning;
import com.example.reservation.ai.dto.AiStreamReset;
import com.example.reservation.ai.dto.AiStreamThinking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * {@link ChatStreamSink} 的 SSE 实现：每个方法对应一个 SSE 命名事件。
 * payload 以对象传入，由 Spring 用 Jackson 序列化为 JSON（UTF-8），中文不转义。
 * 客户端断开 / emitter 已完成时 send 抛异常，统一转 IOException 交 service 提前收尾。
 *
 * @author hyr
 */
@Slf4j
public class SseChatStreamSink implements ChatStreamSink {

    private final SseEmitter emitter;

    public SseChatStreamSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void meta(boolean enabled, boolean degraded, String message) throws IOException {
        send("meta", new AiStreamMeta(enabled, degraded, message));
    }

    @Override
    public void delta(String text) throws IOException {
        send("delta", new AiStreamDelta(text));
    }

    @Override
    public void reset(String text, String message) throws IOException {
        send("reset", new AiStreamReset(text, message));
    }

    @Override
    public void done(String finishReason) throws IOException {
        send("done", new AiStreamDone(finishReason));
    }

    @Override
    public void error(String message) throws IOException {
        send("error", new AiStreamError(message));
    }

    @Override
    public void thinking(AiStreamThinking payload) throws IOException {
        send("thinking", payload);
    }

    @Override
    public void reasoning(AiStreamReasoning payload) throws IOException {
        send("reasoning", payload);
    }

    @Override
    public void plan(AiPlanVO payload) throws IOException {
        // 方案卡事件：前端据此渲染候选教室与「去预约」按钮（不承载任何写操作）
        send("plan", payload);
    }

    /** 发送单个命名事件；IllegalStateException（emitter 已完成，或发送被框架包装为 ISE）转 IOException */
    private void send(String eventName, Object payload) throws IOException {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (IllegalStateException e) {
            log.debug("SSE 发送失败：event={}，原因={}", eventName, e.getMessage());
            throw new IOException("SSE sink closed: " + eventName, e);
        }
    }
}
