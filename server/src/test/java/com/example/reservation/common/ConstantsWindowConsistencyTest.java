package com.example.reservation.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 窗口常量派生一致性守卫（N4 收尾）：确保字符串/小时形态始终由 LocalTime 边界派生，
 * 防止再次出现"两份独立定义"。若本类失败，说明有人重新引入了字面量。
 */
class ConstantsWindowConsistencyTest {

    @Test
    void hourConstantsShouldDeriveFromSlotBoundaries() {
        assertThat(Constants.DAILY_SLOT_START_HOUR).isEqualTo(Constants.DAILY_SLOT_START.getHour());
        assertThat(Constants.DAILY_SLOT_END_HOUR).isEqualTo(Constants.DAILY_SLOT_END.getHour());
        assertThat(Constants.DAILY_AVAILABLE_HOURS)
                .isEqualTo(Constants.DAILY_SLOT_END_HOUR - Constants.DAILY_SLOT_START_HOUR);
    }

    @Test
    void textConstantsShouldMatchSlotBoundaries() {
        assertThat(Constants.DAILY_AVAILABLE_START)
                .isEqualTo(String.format("%02d:00", Constants.DAILY_SLOT_START_HOUR));
        assertThat(Constants.DAILY_AVAILABLE_END)
                .isEqualTo(String.format("%02d:00", Constants.DAILY_SLOT_END_HOUR));
    }

    @Test
    void windowShouldStayAtDocumentedValues() {
        // 业务契约护栏：窗口为 08:00-22:00（14 小时）。
        // 若确需变更窗口，须同步：本测试 + 前端 web/src/utils/booking.js:11-15 的镜像常量。
        assertThat(Constants.DAILY_AVAILABLE_START).isEqualTo("08:00");
        assertThat(Constants.DAILY_AVAILABLE_END).isEqualTo("22:00");
        assertThat(Constants.DAILY_AVAILABLE_HOURS).isEqualTo(14);
    }
}
