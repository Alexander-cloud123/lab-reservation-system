package com.example.reservation.ai.support;

import java.util.Optional;

/**
 * 上游 OpenAI 兼容 SSE 帧解析器（R12 流式问答）
 *
 * 协议要点（SSE）：
 *  - 事件以空行分隔；data: 字段在同一事件内可出现多次，按 \n 拼接；
 *  - : 开头为注释；event:/id:/retry: 等字段本实现忽略（不清空 data 缓冲）；
 *  - 结束哨兵 data: [DONE]。
 *
 * 使用方式：调用方用 BufferedReader 逐行 readLine（已去换行符）后调 feedLine(String)，
 * 返回非空表示空行触发了一次派发。实例非线程安全（每条上游流独占一个）。
 *
 * @author reservation-team
 */
public final class AiSseLineParser {

    /** 当前事件已累积的 data 文本（多行 data 用 \n 连接） */
    private final StringBuilder dataBuffer = new StringBuilder();

    /**
     * 喂入一个物理行（不含 \n/\r\n 行尾；若调用方保留了 \r，本方法兜底剥离）
     *
     * @return 空行触发派发时返回 Frame；其他情况返回 empty
     */
    public Optional<Frame> feedLine(String rawLine) {
        String line = rawLine == null ? ""
                : (rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine);

        // 空行 = 事件边界：派发已累积的 data
        if (line.isEmpty()) {
            return dispatch();
        }
        // SSE 注释行
        if (line.startsWith(":")) {
            return Optional.empty();
        }
        if (line.startsWith("data:")) {
            String value = line.length() > 5 ? line.substring(5) : "";
            // SSE 规范：去除值的一个前导空格
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (!dataBuffer.isEmpty()) {
                dataBuffer.append('\n');
            }
            dataBuffer.append(value);
        }
        // 其余字段行（event:/id:/retry:/未知字段）忽略，不影响 data 缓冲
        return Optional.empty();
    }

    /** 派发当前事件并清空缓冲；无 data 的空事件不产出 */
    private Optional<Frame> dispatch() {
        if (dataBuffer.isEmpty()) {
            return Optional.empty();
        }
        String payload = dataBuffer.toString();
        dataBuffer.setLength(0);
        if ("[DONE]".equals(payload.trim())) {
            return Optional.of(Frame.done());
        }
        return Optional.of(Frame.data(payload));
    }

    /**
     * SSE 帧：Kind.DATA 携带原始 data 文本（通常为 JSON）；Kind.DONE 为结束哨兵
     */
    public record Frame(Kind kind, String payload) {

        public enum Kind {
            DATA, DONE
        }

        static Frame data(String payload) {
            return new Frame(Kind.DATA, payload);
        }

        static Frame done() {
            return new Frame(Kind.DONE, null);
        }
    }
}
