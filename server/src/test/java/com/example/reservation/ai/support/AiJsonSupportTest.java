package com.example.reservation.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型 JSON 输出容错解析单测（AiJsonSupport）
 *
 * <p>覆盖实测到的三类偏离：markdown 代码块包裹、前后夹带说明文字、数组约定只返回单对象；
 * 解析失败一律返回 null（调用方据此降级），绝不抛异常。
 *
 * <p>这里使用真实 ObjectMapper（本类逻辑就是 JSON 解析本身，mock 掉就测不到容错行为）。
 */
class AiJsonSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiJsonSupport support;

    @BeforeEach
    void setUp() {
        support = new AiJsonSupport();
        ReflectionTestUtils.setField(support, "objectMapper", objectMapper);
    }

    /* ==================== readObject ==================== */

    @Test
    @DisplayName("readObject：空输入 / 非对象结构返回 null")
    void readObject_invalidInputs() {
        assertNull(support.readObject(null));
        assertNull(support.readObject("   "));
        assertNull(support.readObject("[1,2,3]"));
        assertNull(support.readObject("不是 JSON"));
    }

    @Test
    @DisplayName("readObject：普通对象 / 代码块包裹 / 夹带说明文字 / 单元素数组 均能解析")
    void readObject_lenientForms() {
        JsonNode plain = support.readObject("{\"date\":\"2026-11-08\",\"capacity\":40}");
        assertNotNull(plain);
        assertEquals("2026-11-08", plain.get("date").asText());

        JsonNode fenced = support.readObject("```json\n{\"date\":\"2026-11-08\"}\n```");
        assertEquals("2026-11-08", fenced.get("date").asText());

        JsonNode wrapped = support.readObject("好的，以下是我的解析结果：{\"date\":\"2026-11-08\"}");
        assertEquals("2026-11-08", wrapped.get("date").asText());

        JsonNode singletonArray = support.readObject("[{\"date\":\"2026-11-08\"}]");
        assertEquals("2026-11-08", singletonArray.get("date").asText());
    }

    /* ==================== extractArray ==================== */

    @Test
    @DisplayName("extractArray：空输入 / 标量 / 无数组字段的对象返回 null")
    void extractArray_invalidInputs() {
        assertNull(support.extractArray(null));
        assertNull(support.extractArray("   "));
        assertNull(support.extractArray("\"just a string\""));
        assertNull(support.extractArray("{\"classroomId\":null}"));
        assertNull(support.extractArray("{\"foo\":\"bar\"}"));
        assertNull(support.extractArray("not json at all"));
    }

    @Test
    @DisplayName("extractArray：顶层数组 / 单对象（含 classroomId）/ 包装键 / 首个非空数组 均能提取")
    void extractArray_lenientForms() {
        JsonNode topArray = support.extractArray("[{\"classroomId\":1},{\"classroomId\":2}]");
        assertTrue(topArray.isArray());
        assertEquals(2, topArray.size());

        JsonNode singleObject = support.extractArray("{\"classroomId\":3,\"reason\":\"全天空闲\"}");
        assertTrue(singleObject.isArray());
        assertEquals(3, singleObject.get(0).get("classroomId").asInt());

        JsonNode wrappedArray = support.extractArray("{\"recommendations\":[{\"classroomId\":4}]}");
        assertTrue(wrappedArray.isArray());
        assertEquals(4, wrappedArray.get(0).get("classroomId").asInt());

        JsonNode wrappedObject = support.extractArray("{\"items\":{\"classroomId\":5}}");
        assertTrue(wrappedObject.isArray());
        assertEquals(5, wrappedObject.get(0).get("classroomId").asInt());

        // 兜底：没有常见包装键时取首个非空数组字段
        JsonNode fallback = support.extractArray("{\"foo\":\"bar\",\"rooms\":[{\"classroomId\":6}]}");
        assertTrue(fallback.isArray());
        assertEquals(6, fallback.get(0).get("classroomId").asInt());
    }

    @Test
    @DisplayName("extractArray：代码块 + 说明文字包裹的数组仍可提取")
    void extractArray_fencedAndWrapped() {
        JsonNode fenced = support.extractArray("```json\n[{\"classroomId\":7}]\n```");
        assertEquals(1, fenced.size());

        JsonNode wrapped = support.extractArray("推荐如下：[{\"classroomId\":8}]");
        assertEquals(8, wrapped.get(0).get("classroomId").asInt());
    }
}
