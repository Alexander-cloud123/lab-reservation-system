package com.example.reservation.ai.support;

import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiStreamReasoning;
import com.example.reservation.ai.dto.AiStreamThinking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 思考过程事件记录器单测（R14）：用可推进的假时钟与录制 sink 断言事件序列、耗时口径与中止语义。
 */
class AiThinkingTraceTest {

    /** 假 sink：记录 thinking/reasoning 事件；failOnThinking 时模拟客户端断开 */
    private static class RecordingSink implements ChatStreamSink {
        final List<AiStreamThinking> thinkingEvents = new ArrayList<>();
        final List<String> reasoningTexts = new ArrayList<>();
        boolean failOnThinking;

        @Override
        public void meta(boolean enabled, boolean degraded, String message) {
        }

        @Override
        public void delta(String text) {
        }

        @Override
        public void reset(String text, String message) {
        }

        @Override
        public void done(String finishReason) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void thinking(AiStreamThinking payload) throws IOException {
            if (failOnThinking) {
                throw new IOException("client gone");
            }
            thinkingEvents.add(payload);
        }

        @Override
        public void reasoning(AiStreamReasoning payload) throws IOException {
            reasoningTexts.add(payload.text());
        }
    }

    @Test
    @DisplayName("begin/end：先发 running（无耗时），再发 done 并带同一文案与该步真实耗时")
    void beginEndEmitsRunningThenDone() {
        AtomicLong now = new AtomicLong(1000L);
        RecordingSink sink = new RecordingSink();
        AiThinkingTrace trace = new AiThinkingTrace(sink, now::get);

        trace.begin(AiConstants.THINK_STEP_RETRIEVE, AiConstants.THINK_LABEL_RETRIEVE);
        now.addAndGet(40_000_000L);
        trace.end(AiConstants.THINK_STEP_RETRIEVE);

        assertEquals(2, sink.thinkingEvents.size());
        AiStreamThinking running = sink.thinkingEvents.get(0);
        assertEquals(AiConstants.THINK_STEP_RETRIEVE, running.id());
        assertEquals(AiConstants.THINK_LABEL_RETRIEVE, running.label());
        assertEquals(AiConstants.THINK_STATUS_RUNNING, running.status());
        assertNull(running.elapsedMs(), "running 阶段不报耗时");

        AiStreamThinking done = sink.thinkingEvents.get(1);
        assertEquals(AiConstants.THINK_STATUS_DONE, done.status());
        assertEquals(40L, done.elapsedMs(), "耗时取该步真实纳秒差（40ms）");
        assertFalse(trace.isClosed());
    }

    @Test
    @DisplayName("fail：失败状态同样带该步耗时（用于上游降级时的 generate）")
    void failEmitsFailedWithElapsed() {
        AtomicLong now = new AtomicLong(0L);
        RecordingSink sink = new RecordingSink();
        AiThinkingTrace trace = new AiThinkingTrace(sink, now::get);

        trace.begin(AiConstants.THINK_STEP_GENERATE, AiConstants.THINK_LABEL_GENERATE);
        now.addAndGet(120_000_000L);
        trace.fail(AiConstants.THINK_STEP_GENERATE);

        assertEquals(AiConstants.THINK_STATUS_FAILED, sink.thinkingEvents.get(1).status());
        assertEquals(120L, sink.thinkingEvents.get(1).elapsedMs());
    }

    @Test
    @DisplayName("summary：从记录器创建到汇总的墙钟耗时，文案可为 null（正常生成）或附加原因")
    void summaryMeasuresFromOrigin() {
        AtomicLong now = new AtomicLong(500L);
        RecordingSink sink = new RecordingSink();
        AiThinkingTrace trace = new AiThinkingTrace(sink, now::get);

        trace.begin(AiConstants.THINK_STEP_SCENE, AiConstants.THINK_LABEL_SCENE);
        now.addAndGet(20_000_000L);
        trace.end(AiConstants.THINK_STEP_SCENE);
        trace.summary(null);
        assertEquals(AiConstants.THINK_STEP_SUMMARY, sink.thinkingEvents.get(2).id());
        assertNull(sink.thinkingEvents.get(2).label());
        assertEquals(20L, sink.thinkingEvents.get(2).elapsedMs());

        now.addAndGet(100_000_000L);
        trace.summary(AiConstants.THINK_SUMMARY_DEGRADED);
        assertEquals(120L, sink.thinkingEvents.get(3).elapsedMs(), "汇总按创建时刻累计，而非各步相加减");
        assertEquals(AiConstants.THINK_SUMMARY_DEGRADED, sink.thinkingEvents.get(3).label());
    }

    @Test
    @DisplayName("连接已断：thinking 写入失败后置 closed，后续步骤与推理一律不再写（不装作还在思考）")
    void closedStopsFurtherEvents() {
        AtomicLong now = new AtomicLong(0L);
        RecordingSink sink = new RecordingSink();
        sink.failOnThinking = true;
        AiThinkingTrace trace = new AiThinkingTrace(sink, now::get);

        trace.begin(AiConstants.THINK_STEP_SCENE, AiConstants.THINK_LABEL_SCENE);
        trace.end(AiConstants.THINK_STEP_SCENE);
        trace.summary(null);
        trace.reasoning("不应写入");

        assertTrue(trace.isClosed());
        assertTrue(sink.thinkingEvents.isEmpty(), "失败后不得再记录任何步骤");
        assertTrue(sink.reasoningTexts.isEmpty());
    }

    @Test
    @DisplayName("reasoning：非空片段按序透传；空片段忽略；写入失败抛 UncheckedIOException（中断上游）")
    void reasoningPropagation() {
        AtomicLong now = new AtomicLong(0L);
        RecordingSink sink = new RecordingSink();
        AiThinkingTrace trace = new AiThinkingTrace(sink, now::get);

        trace.reasoning("第一段");
        trace.reasoning("");
        trace.reasoning(null);
        trace.reasoning("第二段");
        assertEquals(List.of("第一段", "第二段"), sink.reasoningTexts);

        sink.failOnThinking = false;
        ChatStreamSink failing = new RecordingSink() {
            @Override
            public void reasoning(AiStreamReasoning payload) throws IOException {
                throw new IOException("write failed");
            }
        };
        AiThinkingTrace failingTrace = new AiThinkingTrace(failing, now::get);
        assertThrows(UncheckedIOException.class, () -> failingTrace.reasoning("推理"));
        assertTrue(failingTrace.isClosed());
    }
}
