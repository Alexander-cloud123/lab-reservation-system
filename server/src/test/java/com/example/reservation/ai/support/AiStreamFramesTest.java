package com.example.reservation.ai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上游 SSE data 帧纯函数单测（R13-4，替代 R12 行状态机测试中「帧 payload → delta」部分；
 * SSE 行分帧本身已由 WebClient 负责，不再是本项目代码）。
 */
class AiStreamFramesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("isDone：精确匹配 [DONE]，容忍两侧空白；null/空串/JSON 帧均为 false")
    void doneSentinel() {
        assertTrue(AiStreamFrames.isDone("[DONE]"));
        assertTrue(AiStreamFrames.isDone("  [DONE]  "));
        assertFalse(AiStreamFrames.isDone(null));
        assertFalse(AiStreamFrames.isDone(""));
        assertFalse(AiStreamFrames.isDone("{\"choices\":[]}"));
        assertFalse(AiStreamFrames.isDone("[DONE]-extra"));
    }

    @Test
    @DisplayName("正常 chunk：提取 delta.content 与 finish_reason")
    void extractContentAndFinish() {
        String payload = "{\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}";
        AiStreamFrames.ChunkDelta delta = AiStreamFrames.tryExtract(payload, mapper).orElseThrow();
        assertEquals("你好", delta.content());
        assertNull(delta.finishReason());

        String end = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}";
        AiStreamFrames.ChunkDelta tail = AiStreamFrames.tryExtract(end, mapper).orElseThrow();
        assertNull(tail.content());
        assertEquals("stop", tail.finishReason());
    }

    @Test
    @DisplayName("空 choices / 无 delta 结构：返回空 Optional 不报错")
    void emptyChoicesSkipped() {
        assertTrue(AiStreamFrames.tryExtract("{\"choices\":[]}", mapper).isEmpty());
        assertTrue(AiStreamFrames.tryExtract("{}", mapper).isEmpty());
    }

    @Test
    @DisplayName("脏帧（非 JSON）/ null / 空串：返回空 Optional，不抛异常（调用方据此跳过整帧）")
    void dirtyFrameSkipped() {
        assertTrue(AiStreamFrames.tryExtract("not-a-json", mapper).isEmpty());
        assertTrue(AiStreamFrames.tryExtract("<html>502</html>", mapper).isEmpty());
        assertTrue(AiStreamFrames.tryExtract(null, mapper).isEmpty());
        assertTrue(AiStreamFrames.tryExtract("", mapper).isEmpty());
    }

    @Test
    @DisplayName("空字符串 content 不产生增量（与 R12 口径一致：isTextual 且非空才算）")
    void blankContentIgnored() {
        Optional<AiStreamFrames.ChunkDelta> delta =
                AiStreamFrames.tryExtract("{\"choices\":[{\"delta\":{\"content\":\"\"}}]}", mapper);
        assertTrue(delta.isPresent());
        assertNull(delta.get().content());
    }

    @Test
    @DisplayName("推理帧：reasoning_content 单独提取，不并入正文；content+reasoning 同帧时两者都取")
    void extractReasoningContent() {
        String reasoningOnly = "{\"choices\":[{\"delta\":{\"reasoning_content\":\"先确认问题\"}}]}";
        AiStreamFrames.ChunkDelta delta = AiStreamFrames.tryExtract(reasoningOnly, mapper).orElseThrow();
        assertEquals("先确认问题", delta.reasoning());
        assertNull(delta.content(), "推理帧不应产生回答正文");

        String both = "{\"choices\":[{\"delta\":{\"reasoning_content\":\"想一想\",\"content\":\"答案\"}}]}";
        AiStreamFrames.ChunkDelta mixed = AiStreamFrames.tryExtract(both, mapper).orElseThrow();
        assertEquals("想一想", mixed.reasoning());
        assertEquals("答案", mixed.content());
    }

    @Test
    @DisplayName("推理别名容错：部分网关用 reasoning 字段；空串视为无推理；无推理时字段为 null")
    void extractReasoningAlias() {
        String alias = "{\"choices\":[{\"delta\":{\"reasoning\":\"别名推理\"}}]}";
        assertEquals("别名推理", AiStreamFrames.tryExtract(alias, mapper).orElseThrow().reasoning());

        String blank = "{\"choices\":[{\"delta\":{\"reasoning_content\":\"\"}}]}";
        assertNull(AiStreamFrames.tryExtract(blank, mapper).orElseThrow().reasoning());

        String plain = "{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}";
        assertNull(AiStreamFrames.tryExtract(plain, mapper).orElseThrow().reasoning());
    }
}
