package com.example.reservation.ai.support;

import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiStreamReasoning;
import com.example.reservation.ai.dto.AiStreamThinking;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 流式问答「思考过程」事件记录器（R14）：把真实执行流水线转成 thinking/reasoning 事件。
 *
 * <p>设计要点：
 * <ul>
 *   <li>步骤耗时由 {@link System#nanoTime()} 差值计算，构造可注入时钟，便于单测；</li>
 *   <li>thinking 事件写入失败视为连接已关闭：置 closed 并静默跳过后续步骤，不伪装进度；</li>
 *   <li>reasoning 事件由上游读流线程回调，写入失败按中断契约抛 {@link UncheckedIOException}，
 *       由传输层 dispose 上游（与 delta 中断语义一致）；</li>
 *   <li>summary 的 elapsedMs 是「入口 → 首个回答 delta」的墙钟时间，不是各步骤耗时之和
 *       （步骤之间还有调度与网络等待，文档中如实说明口径）。</li>
 * </ul>
 */
@Slf4j
public final class AiThinkingTrace {

    private final ChatStreamSink sink;
    private final LongSupplier clock;
    private final long originNs;
    /** 已开始但未结束的步骤：id → 起始纳秒 */
    private final Map<String, Long> startedNs = new LinkedHashMap<>();
    /** 已开始步骤的展示文案：id → label */
    private final Map<String, String> labels = new LinkedHashMap<>();
    private boolean closed;

    public AiThinkingTrace(ChatStreamSink sink) {
        this(sink, System::nanoTime);
    }

    /** 可注入时钟的构造器（单测用假时钟） */
    public AiThinkingTrace(ChatStreamSink sink, LongSupplier clock) {
        this.sink = sink;
        this.clock = clock;
        this.originNs = clock.getAsLong();
    }

    /** 开始一步：立即发出 running 事件 */
    public void begin(String id, String label) {
        labels.put(id, label);
        startedNs.put(id, clock.getAsLong());
        emit(new AiStreamThinking(id, label, AiConstants.THINK_STATUS_RUNNING, null));
    }

    /** 步骤正常结束：发出 done 事件并带该步真实耗时 */
    public void end(String id) {
        finish(id, AiConstants.THINK_STATUS_DONE);
    }

    /** 步骤失败（如上游降级）：发出 failed 事件并带该步真实耗时 */
    public void fail(String id) {
        finish(id, AiConstants.THINK_STATUS_FAILED);
    }

    /**
     * 思考计时汇总：elapsedMs 为入口到当前时刻的墙钟时间。
     *
     * @param label 可选摘要文案（命中缓存 / 已降级为本地规则 / 范围判断完成）；null 时前端展示「已思考 X 秒」
     */
    public void summary(String label) {
        emit(new AiStreamThinking(AiConstants.THINK_STEP_SUMMARY, label,
                AiConstants.THINK_STATUS_DONE, millisSince(originNs)));
    }

    /**
     * 透传模型推理片段；写入失败视为客户端断开，抛 {@link UncheckedIOException} 中止上游读取。
     */
    public void reasoning(String text) {
        if (closed || text == null || text.isEmpty()) {
            return;
        }
        try {
            sink.reasoning(new AiStreamReasoning(text));
        } catch (IOException e) {
            closed = true;
            log.debug("思考推理事件写入失败（视为客户端断开）：{}", e.getMessage());
            throw new UncheckedIOException(e);
        }
    }

    /** 连接是否已被判定关闭（thinking/reasoning 写入失败后为 true） */
    public boolean isClosed() {
        return closed;
    }

    private void finish(String id, String status) {
        Long startNs = startedNs.remove(id);
        String label = labels.remove(id);
        long elapsedMs = startNs == null ? 0L : millisSince(startNs);
        emit(new AiStreamThinking(id, label, status, elapsedMs));
    }

    private void emit(AiStreamThinking event) {
        if (closed) {
            return;
        }
        try {
            sink.thinking(event);
        } catch (IOException e) {
            // 思考过程属于辅助信息：连接已断时不阻断主流程判定，由后续 delta/done 写入再次暴露
            closed = true;
            log.debug("思考事件写入失败：event={}，原因={}", event.id(), e.getMessage());
        }
    }

    private long millisSince(long startNs) {
        return Math.max(0L, (clock.getAsLong() - startNs) / 1_000_000);
    }
}
