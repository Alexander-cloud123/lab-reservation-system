package com.example.reservation.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 日期严格解析回归测试。 */
class TimeUtilTest {

    @Test
    @DisplayName("拒绝不存在的 2 月 30 日，不归一化为月末")
    void parseDate_rejectsInvalidCalendarDate() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> TimeUtil.parseDate("2026-02-30"));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("接受真实闰日并拒绝非闰年 2 月 29 日")
    void parseDate_validatesLeapDay() {
        assertEquals(LocalDate.of(2028, 2, 29), TimeUtil.parseDate("2028-02-29"));
        assertThrows(BusinessException.class, () -> TimeUtil.parseDate("2026-02-29"));
    }

    @Test
    @DisplayName("拒绝非零填充日期")
    void parseDate_rejectsNonIsoDate() {
        assertThrows(BusinessException.class, () -> TimeUtil.parseDate("2026-2-03"));
    }
}
