package com.example.reservation.ai.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AiStreamTextTest {

    @Test
    @DisplayName("分段：空串/空白/ null 返回空列表")
    void chunksBlank() {
        assertEquals(List.of(), AiStreamText.chunks("", 20));
        assertEquals(List.of(), AiStreamText.chunks("   ", 20));
        assertEquals(List.of(), AiStreamText.chunks(null, 20));
    }

    @Test
    @DisplayName("分段：短于步长一整段；正好整除不多空段")
    void chunksShortAndExact() {
        assertEquals(List.of("abc"), AiStreamText.chunks("abc", 20));
        assertEquals(List.of("ab", "cd"), AiStreamText.chunks("abcd", 2));
    }

    @Test
    @DisplayName("分段：非整除最后一段为余数；拼接还原原文")
    void chunksRemainderAndJoin() {
        String text = "0123456789ABCDEFGHIJ"; // 20 字
        List<String> chunks = AiStreamText.chunks(text, 8);
        assertEquals(List.of("01234567", "89ABCDEF", "GHIJ"), chunks);
        assertEquals(text, String.join("", chunks));
    }

    @Test
    @DisplayName("步长非法（<=0）按 1 兜底，不抛异常")
    void chunksIllegalSize() {
        assertEquals(List.of("a", "b"), AiStreamText.chunks("ab", 0));
    }

    @Test
    @DisplayName("代码围栏：整体 ``` 包裹时剥离围栏与语言标记，正文不动")
    void stripFenceWrapped() {
        String raw = "```markdown\n你好，世界\n```";
        assertEquals("你好，世界", AiStreamText.stripCodeFence(raw));
    }

    @Test
    @DisplayName("代码围栏：无围栏时原样返回（不 trim、不改写）")
    void stripFenceNone() {
        String raw = "  普通回答，没有围栏  ";
        assertSame(raw, AiStreamText.stripCodeFence(raw));
    }

    @Test
    @DisplayName("代码围栏：只有围栏头不成对时不处理，避免误伤")
    void stripFenceMalformed() {
        String raw = "```半截回答";
        assertEquals(raw, AiStreamText.stripCodeFence(raw));
    }

    @Test
    @DisplayName("代码围栏：有围栏头+换行但无收尾时原样返回")
    void stripFenceOpenedUnclosed() {
        String raw = "```java\ncode";
        assertSame(raw, AiStreamText.stripCodeFence(raw));
    }

    @Test
    @DisplayName("代码围栏：无语言标记的完整围栏正常剥离")
    void stripFenceNoLanguage() {
        assertEquals("正文", AiStreamText.stripCodeFence("```\n正文\n```"));
    }
}
