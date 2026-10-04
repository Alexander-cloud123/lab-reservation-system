package com.example.reservation.ai.support;

import com.example.reservation.ai.config.AiConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 模型响应体解析纯单测（本次改动：token 用量与 finish_reason 观测）
 *
 * 覆盖：完整响应 / usage 缺失 / total 缺失兜底 / finish_reason=length / 空 choices / 内容缺失 / 非 JSON / 空白输入。
 * 全部为纯单测（自行注入 ObjectMapper，不起 Spring 上下文、不消耗上游额度）。
 *
 * @author reservation-team
 */
class AiResponseParserTest {

    private AiResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new AiResponseParser();
        ReflectionTestUtils.setField(parser, "objectMapper", new ObjectMapper());
    }

    @Test
    @DisplayName("完整响应：内容 + usage 三项 + finish_reason=stop 全部解析")
    void parseFullResponse() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30,\"total_tokens\":150}}";
        AiResponseParser.ParsedResponse r = parser.parse(body);
        assertEquals("hello", r.content());
        assertEquals(AiConstants.FINISH_REASON_STOP, r.usage().finishReason());
        assertEquals(120L, r.usage().promptTokens());
        assertEquals(30L, r.usage().completionTokens());
        assertEquals(150L, r.usage().totalTokens());
    }

    @Test
    @DisplayName("usage 缺失：内容与 finish_reason 仍可解析，token 计数为 0（如实体现不可得）")
    void parseWithoutUsage() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}";
        AiResponseParser.ParsedResponse r = parser.parse(body);
        assertEquals("hi", r.content());
        assertEquals(AiConstants.FINISH_REASON_STOP, r.usage().finishReason());
        assertEquals(0L, r.usage().totalTokens());
        assertEquals(0L, r.usage().promptTokens());
    }

    @Test
    @DisplayName("usage 只给分项：total 按「输入+输出」兜底")
    void parseTotalFallbackToSum() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}";
        assertEquals(10L, parser.parse(body).usage().totalTokens());
    }

    @Test
    @DisplayName("finish_reason=length：截断信号被原样透出（供指标识别 max_tokens 截断）")
    void parseFinishReasonLength() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"{\\\"part\"},\"finish_reason\":\"length\"}],"
                + "\"usage\":{\"total_tokens\":1024}}";
        AiResponseParser.ParsedResponse r = parser.parse(body);
        assertEquals(AiConstants.FINISH_REASON_LENGTH, r.usage().finishReason());
        assertEquals(1024L, r.usage().totalTokens());
    }

    @Test
    @DisplayName("choices 为空数组：返回空结果（内容 null、用量 EMPTY）")
    void parseEmptyChoices() {
        AiResponseParser.ParsedResponse r = parser.parse("{\"choices\":[]}");
        assertNull(r.content());
        assertEquals(AiUsage.EMPTY, r.usage());
    }

    @Test
    @DisplayName("choices[0].message 无 content：内容为 null，不抛异常")
    void parseContentMissing() {
        AiResponseParser.ParsedResponse r = parser.parse("{\"choices\":[{\"message\":{},\"finish_reason\":\"stop\"}]}");
        assertNull(r.content());
        assertEquals(AiConstants.FINISH_REASON_STOP, r.usage().finishReason());
    }

    @Test
    @DisplayName("非 JSON 响应体（如网关 HTML）：返回空结果，不抛异常")
    void parseNonJsonBody() {
        AiResponseParser.ParsedResponse r = parser.parse("<html>502 Bad Gateway</html>");
        assertNull(r.content());
        assertEquals(AiUsage.EMPTY, r.usage());
    }

    @Test
    @DisplayName("空白 / null 输入：返回空结果")
    void parseBlankBody() {
        assertNull(parser.parse("   ").content());
        assertNull(parser.parse(null).content());
    }
}