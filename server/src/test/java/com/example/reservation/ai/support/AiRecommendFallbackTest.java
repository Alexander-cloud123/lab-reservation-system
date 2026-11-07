package com.example.reservation.ai.support;

import com.example.reservation.ai.dto.AiRecommendItemVO;
import com.example.reservation.entity.Classroom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智能推荐本地降级单测（spec.md 6.3：大模型不可用时的规则打分兜底）
 *
 * <p>口径：空闲度(10) > 楼栋偏好(5) > 类型偏好(4) > 容量匹配(3) > 历史活跃(1，封顶 5)；
 * 分数相同时按教室 ID 升序，保证同一份数据每次推荐结果一致（演示可复现）。
 */
class AiRecommendFallbackTest {

    private final AiRecommendFallback fallback = new AiRecommendFallback();

    private static final AiRecommendFallback.UserPreference PREF =
            new AiRecommendFallback.UserPreference("信息楼", 1, "14:00-16:00", 60);

    @Test
    @DisplayName("rankAndTop：按分数降序排序，并截断到 Top N")
    void rankAndTop_sortsByScoreAndLimits() {
        // c1 全空闲且命中三项偏好 → 10+5+4+3+0 = 22
        // c2 仍有时段但历史活跃 3 次 → 5+5+4+3+3 = 20
        // c3 全空闲但不命中任何偏好 → 10
        var c1 = candidate(1L, "A101", "信息楼", 1, 60, true, 0);
        var c2 = candidate(2L, "A102", "信息楼", 1, 60, false, 3);
        var c3 = candidate(3L, "B201", "实验楼", 2, 40, true, 0);

        List<AiRecommendItemVO> top2 = fallback.rankAndTop(List.of(c3, c2, c1), PREF, 2);

        assertEquals(2, top2.size());
        assertEquals(1L, top2.get(0).getClassroomId());
        assertEquals(2L, top2.get(1).getClassroomId());

        List<AiRecommendItemVO> all = fallback.rankAndTop(List.of(c3, c2, c1), PREF, 3);
        assertEquals(List.of(1L, 2L, 3L), all.stream().map(AiRecommendItemVO::getClassroomId).toList());
    }

    @Test
    @DisplayName("rankAndTop：同分时按教室 ID 升序；topCount 为 0 或负数时返回空列表")
    void rankAndTop_tieBreakAndEmptyLimit() {
        var idA = candidate(4L, "A104", "信息楼", 1, 60, true, 0);
        var idB = candidate(5L, "A105", "信息楼", 1, 60, true, 0);

        List<AiRecommendItemVO> result = fallback.rankAndTop(List.of(idB, idA), PREF, 10);
        assertEquals(List.of(4L, 5L), result.stream().map(AiRecommendItemVO::getClassroomId).toList());

        assertTrue(fallback.rankAndTop(List.of(idA), PREF, 0).isEmpty());
        assertTrue(fallback.rankAndTop(List.of(idA), PREF, -1).isEmpty());
    }

    @Test
    @DisplayName("推荐理由：命中维度按「空闲度 → 楼栋 → 类型 → 容量」顺序拼接")
    void buildReason_listsMatchedDimensions() {
        List<AiRecommendItemVO> result = fallback.rankAndTop(
                List.of(candidate(1L, "A101", "信息楼", 1, 60, true, 0)), PREF, 1);

        assertEquals("明日全天空闲，您常约的信息楼，符合您常约的类型，容量接近您的常用规模",
                result.get(0).getReason());
    }

    @Test
    @DisplayName("推荐理由：非全天空闲、无偏好命中时只保留基础描述")
    void buildReason_fallbackText() {
        AiRecommendFallback.UserPreference noPref =
                new AiRecommendFallback.UserPreference(null, null, null, 0);
        List<AiRecommendItemVO> result = fallback.rankAndTop(
                List.of(candidate(9L, "C301", "老旧楼", 3, 20, false, 0)), noPref, 1);

        assertEquals("明日仍有空闲时段", result.get(0).getReason());
    }

    @Test
    @DisplayName("推荐项字段：教室基础信息透传（ID / 名称 / 楼栋 / 房间号 / 类型 / 容量）")
    void rankAndTop_mapsClassroomFields() {
        List<AiRecommendItemVO> result = fallback.rankAndTop(
                List.of(candidate(1L, "A101", "信息楼", 1, 60, true, 0)), PREF, 1);

        AiRecommendItemVO item = result.get(0);
        assertEquals(1L, item.getClassroomId());
        assertEquals("A101", item.getName());
        assertEquals("信息楼", item.getBuilding());
        assertEquals("A1", item.getRoomNo());
        assertEquals(1, item.getType());
        assertEquals(60, item.getCapacity());
    }

    /* ==================== 工具方法 ==================== */

    private static AiRecommendFallback.RecommendCandidate candidate(Long id, String name, String building,
                                                                   Integer type, Integer capacity,
                                                                   boolean fullyFree, int occupied) {
        Classroom c = new Classroom();
        c.setId(id);
        c.setName(name);
        c.setBuilding(building);
        c.setRoomNo("A" + id);
        c.setType(type);
        c.setCapacity(capacity);
        return new AiRecommendFallback.RecommendCandidate(c, fullyFree, occupied);
    }
}
