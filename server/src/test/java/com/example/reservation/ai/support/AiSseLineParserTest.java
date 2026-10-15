package com.example.reservation.ai.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游 SSE 帧解析器单测：正常 data 帧 / [DONE] 哨兵 / 多行 data 拼接 /
 * 注释与 event 字段行忽略 / CRLF 兼容 / 空帧不产出。
 */
class AiSseLineParserTest {

    private AiSseLineParser parser;

    @BeforeEach
    void setUp() {
        parser = new AiSseLineParser();
    }

    /** 喂入一帧的全部行（行内不含换行符），返回空行派发结果 */
    private Optional<AiSseLineParser.Frame> feed(String... lines) {
        Optional<AiSseLineParser.Frame> last = Optional.empty();
        for (String line : lines) {
            Optional<AiSseLineParser.Frame> f = parser.feedLine(line);
            if (f.isPresent()) {
                last = f;
            }
        }
        // 等价于 parser.feedLine("").or(() -> last)；last 在循环中被重新赋值，
        // 不是 effectively final 无法被 lambda 捕获，故用三元表达式保持相同语义。
        Optional<AiSseLineParser.Frame> dispatched = parser.feedLine("");
        return dispatched.isPresent() ? dispatched : last;
    }

    @Test
    @DisplayName("data 帧：空行派发，payload 原样取出")
    void dataFrameDispatchedOnBlankLine() {
        Optional<AiSseLineParser.Frame> before = parser.feedLine("data: {\"choices\":[]}");
        assertFalse(before.isPresent(), "未到空行（帧边界）前不派发");
        Optional<AiSseLineParser.Frame> frame = parser.feedLine("");
        assertTrue(frame.isPresent());
        assertEquals(AiSseLineParser.Frame.Kind.DATA, frame.get().kind());
        assertEquals("{\"choices\":[]}", frame.get().payload());
    }

    @Test
    @DisplayName("[DONE] 哨兵帧识别为 DONE，payload 为 null")
    void doneFrame() {
        Optional<AiSseLineParser.Frame> frame = feed("data: [DONE]");
        assertTrue(frame.isPresent());
        assertEquals(AiSseLineParser.Frame.Kind.DONE, frame.get().kind());
        assertNull(frame.get().payload());
    }

    @Test
    @DisplayName("多行 data 在同一帧内按 \\n 拼接（去掉一个前导空格）")
    void multiLineDataJoined() {
        Optional<AiSseLineParser.Frame> frame = feed("data: 第一行", "data:第二行");
        assertEquals("第一行\n第二行", frame.get().payload());
    }

    @Test
    @DisplayName("':' 注释行与 event:/id: 字段行不派发，也不清空 data 缓冲")
    void commentAndFieldLinesIgnored() {
        Optional<AiSseLineParser.Frame> frame = feed(
                ": this is a comment", "event: delta", "id: 12", "data: hello");
        assertEquals(AiSseLineParser.Frame.Kind.DATA, frame.get().kind());
        assertEquals("hello", frame.get().payload());
    }

    @Test
    @DisplayName("CRLF：行尾 \\r 被剥离（部分容器/代理会保留 CR）")
    void crlfTolerated() {
        parser.feedLine("data: abc\r");
        Optional<AiSseLineParser.Frame> frame = parser.feedLine("\r");
        assertEquals("abc", frame.get().payload());
    }

    @Test
    @DisplayName("空帧（空行前无 data）不产出")
    void emptyFrameNotDispatched() {
        assertFalse(parser.feedLine("").isPresent());
        assertFalse(parser.feedLine("event: delta").isPresent());
        assertFalse(parser.feedLine("").isPresent());
    }

    @Test
    @DisplayName("两帧连续解析：缓冲在派发后正确重置")
    void twoFramesSequential() {
        parser.feedLine("data: one");
        assertEquals("one", parser.feedLine("").get().payload());
        parser.feedLine("data: two");
        assertEquals("two", parser.feedLine("").get().payload());
    }
}
