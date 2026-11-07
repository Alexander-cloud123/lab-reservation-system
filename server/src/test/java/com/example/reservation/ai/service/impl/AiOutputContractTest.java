package com.example.reservation.ai.service.impl;

import com.example.reservation.ai.dto.AiParseVO;
import com.example.reservation.ai.dto.AiRecommendItemVO;
import com.example.reservation.ai.support.AiJsonSupport;
import com.example.reservation.ai.support.AiRecommendFallback;
import com.example.reservation.common.Constants;
import com.example.reservation.entity.Classroom;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** AI 推荐 Top3 与预约解析语义契约测试，不依赖上游或数据库。 */
class AiOutputContractTest {

    private AiParseServiceImpl parseService;
    private AiRecommendServiceImpl recommendService;
    private List<AiRecommendFallback.RecommendCandidate> candidates;

    @BeforeEach
    void setUp() {
        AiJsonSupport jsonSupport = new AiJsonSupport();
        ReflectionTestUtils.setField(jsonSupport, "objectMapper", new ObjectMapper());
        parseService = new AiParseServiceImpl();
        recommendService = new AiRecommendServiceImpl();
        ReflectionTestUtils.setField(parseService, "aiJsonSupport", jsonSupport);
        ReflectionTestUtils.setField(recommendService, "aiJsonSupport", jsonSupport);
        candidates = List.of(candidate(1L), candidate(2L), candidate(3L));
    }

    @Test
    @DisplayName("推荐模型只给 1-2 项、重复项或非法 ID 都必须回退，恰好 Top3 才接受")
    void recommend_requiresExactValidTop3() {
        String two = "{\"recommendations\":[{\"classroomId\":1},{\"classroomId\":2}]}";
        String duplicate = "{\"recommendations\":[{\"classroomId\":1},{\"classroomId\":1},{\"classroomId\":2}]}";
        String invalid = "{\"recommendations\":[{\"classroomId\":1},{\"classroomId\":2},{\"classroomId\":99}]}";
        String valid = "{\"recommendations\":[{\"classroomId\":1},{\"classroomId\":2},{\"classroomId\":3}]}";

        assertNull(invokeTop(two));
        assertNull(invokeTop(duplicate));
        assertNull(invokeTop(invalid));
        List<AiRecommendItemVO> result = invokeTop(valid);
        assertNotNull(result);
        assertEquals(3, result.size());
    }

    @Test
    @DisplayName("解析模型拒绝过去日期、相同起止时间、非正人数及超长用途")
    void parse_rejectsInvalidSemantics() {
        String tomorrow = LocalDate.now().plusDays(1).toString();
        String yesterday = LocalDate.now().minusDays(1).toString();
        assertNull(invokeParse(json(tomorrow, "10:00", "10:00", 20, "自习")));
        assertNull(invokeParse(json(yesterday, "10:00", "11:00", 20, "自习")));
        assertNull(invokeParse(json(tomorrow, "10:00", "11:00", 0, "自习")));
        assertNull(invokeParse(json(tomorrow, "10:00", "11:00", 20, "x".repeat(Constants.PURPOSE_MAX_LENGTH + 1))));

        AiParseVO valid = invokeParse(json(tomorrow, "10:00", "11:00", 20, "自习"));
        assertNotNull(valid);
        assertEquals(tomorrow, valid.getDate());
    }

    private List<AiRecommendItemVO> invokeTop(String json) {
        return ReflectionTestUtils.invokeMethod(recommendService, "parseModelTop", json, candidates);
    }

    private AiParseVO invokeParse(String json) {
        return ReflectionTestUtils.invokeMethod(parseService, "parseModelOutput", json);
    }

    private String json(String date, String start, String end, int capacity, String purpose) {
        try {
            return new ObjectMapper().writeValueAsString(new ObjectMapper().createObjectNode()
                    .put("date", date)
                    .put("startTime", start)
                    .put("endTime", end)
                    .put("capacity", capacity)
                    .put("purpose", purpose));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AiRecommendFallback.RecommendCandidate candidate(long id) {
        Classroom room = new Classroom();
        room.setId(id);
        room.setName("教室" + id);
        room.setBuilding("测试楼");
        room.setRoomNo("A" + id);
        room.setType(Constants.CLASSROOM_TYPE_NORMAL);
        room.setCapacity(60);
        return new AiRecommendFallback.RecommendCandidate(room, true, 0);
    }
}
