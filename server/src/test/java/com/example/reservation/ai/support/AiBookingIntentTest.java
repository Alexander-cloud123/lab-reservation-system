package com.example.reservation.ai.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预约意图识别单测（R15-1）：纯规则，不调模型。
 * 断言「帮我约」类动作命中、「查询/规则」类问题不命中，避免误触发方案卡。
 */
class AiBookingIntentTest {

    @Test
    @DisplayName("请求动作 + 预约线索：进入规划分支")
    void matchesBookingRequests() {
        assertTrue(AiBookingIntent.matches("帮我约明天的机房"));
        assertTrue(AiBookingIntent.matches("我想约明天下午两点到四点的实验室"));
        assertTrue(AiBookingIntent.matches("给我订一间 30 人的教室"));
        assertTrue(AiBookingIntent.matches("帮忙找一间明天上午空着的机房"));
        assertTrue(AiBookingIntent.matches("明天下午有空吗，帮我约个教室"));
        assertTrue(AiBookingIntent.matches("替我订明天下午 2 点到 4 点的实验室"));
        assertTrue(AiBookingIntent.matches("要约后天上午的普通教室"));
        assertTrue(AiBookingIntent.matches("还能约明天下午的机房吗"));
    }

    @Test
    @DisplayName("查询 / 规则 / 记录类问题：不进规划分支")
    void rejectsQueries() {
        assertFalse(AiBookingIntent.matches("我明天有哪些预约"));
        assertFalse(AiBookingIntent.matches("怎么取消预约"));
        assertFalse(AiBookingIntent.matches("审核要多久"));
        assertFalse(AiBookingIntent.matches("帮我查一下我的预约记录"));
        assertFalse(AiBookingIntent.matches("有没有空教室"));
        assertFalse(AiBookingIntent.matches("预约规则是什么"));
        assertFalse(AiBookingIntent.matches("查看我的预约状态"));
        assertFalse(AiBookingIntent.matches("今天几点了"));
        assertFalse(AiBookingIntent.matches(null));
        assertFalse(AiBookingIntent.matches("  "));
    }
}
