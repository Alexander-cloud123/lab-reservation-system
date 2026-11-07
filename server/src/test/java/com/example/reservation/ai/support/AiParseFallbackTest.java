package com.example.reservation.ai.support;

import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiParseVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自然语言解析本地降级单测（spec.md 6.3：降级链在任何外部输入下都不抛异常、不 500）
 *
 * <p>黑盒口径：只喂自然语言文本，断言解析出的日期 / 时段 / 人数 / 类型 / 用途与 error 标记，
 * 不直接测私有正则，保证重构正则实现时用例仍然有效。
 */
class AiParseFallbackTest {

    private final AiParseFallback fallback = new AiParseFallback();

    @Test
    @DisplayName("完整描述：明天下午 2 点到 4 点 / 40 人 / 实验室 → 日期、时段、人数、类型全部命中")
    void parseFullSentence() {
        AiParseVO vo = fallback.parseFallback("明天下午2点到4点，40人，实验室，答辩");

        assertEquals(LocalDate.now().plusDays(1).toString(), vo.getDate());
        assertEquals("14:00", vo.getStartTime());
        assertEquals("16:00", vo.getEndTime());
        assertEquals(40, vo.getCapacity());
        assertEquals(AiConstants.ROOM_TYPE_LAB, vo.getRoomType());
        // 用途关键词按声明顺序取第一个命中项：「实验室」先命中「实验」，答辩在列表更后
        assertEquals("实验", vo.getPurpose());
        assertNull(vo.getError());
        assertTrue(vo.isEnabled());
    }

    @Test
    @DisplayName("今天 / 数字时间 / 机房 / 人数 / 自习：另一组组合命中")
    void parseTodayAndDigitalTime() {
        AiParseVO vo = fallback.parseFallback("今天 8:30-11:00 机房 30人 自习");

        assertEquals(LocalDate.now().toString(), vo.getDate());
        assertEquals("08:30", vo.getStartTime());
        assertEquals("11:00", vo.getEndTime());
        assertEquals(30, vo.getCapacity());
        assertEquals(AiConstants.ROOM_TYPE_COMPUTER, vo.getRoomType());
        assertEquals("自习", vo.getPurpose());
    }

    @Test
    @DisplayName("日期形态：后天 / 周天 / 2026-09-12 / 2026 年 9 月 12 日 / 9 月 12 日 均可识别")
    void parseDateVariants() {
        assertEquals(LocalDate.now().plusDays(2).toString(),
                fallback.parseFallback("后天下午 14:00").getDate());
        assertEquals(LocalDate.of(2026, 9, 12).toString(),
                fallback.parseFallback("2026-09-12 14:00").getDate());
        assertEquals(LocalDate.of(2026, 9, 12).toString(),
                fallback.parseFallback("2026年9月12日 14:00").getDate());
        assertEquals(LocalDate.of(LocalDate.now().getYear(), 9, 12).toString(),
                fallback.parseFallback("9月12日 14:00").getDate());

        // 周 X：只断言性质（下周同一天、且严格晚于今天），不复制实现里的取模公式
        AiParseVO week = fallback.parseFallback("周天下午 14:00");
        LocalDate date = LocalDate.parse(week.getDate());
        assertEquals(DayOfWeek.SUNDAY, date.getDayOfWeek());
        assertTrue(date.isAfter(LocalDate.now()));
    }

    @Test
    @DisplayName("仅识别到开始时间时默认 +2 小时；越过 22:00 截断，且开始时间退到 21:00")
    void parseSingleTimeExpandsAndClipsToWindow() {
        AiParseVO vo = fallback.parseFallback("明天 14:00");
        assertEquals("14:00", vo.getStartTime());
        assertEquals("16:00", vo.getEndTime());

        // 21:30 起 2h 会超出 22:00：结束截断到 22:00，开始按「22:00 - 1h」退回 21:00
        AiParseVO late = fallback.parseFallback("明天 21:30");
        assertEquals("21:00", late.getStartTime());
        assertEquals("22:00", late.getEndTime());
    }

    @Test
    @DisplayName("中文 12 小时制：下午 2 点半 / 上午 12 点 / 中午 12 点")
    void parseChineseTwelveHourClock() {
        AiParseVO afternoon = fallback.parseFallback("明天下午2点半");
        assertEquals("14:30", afternoon.getStartTime());
        assertEquals("16:30", afternoon.getEndTime());

        AiParseVO midnight = fallback.parseFallback("明天上午12点");
        assertEquals("00:00", midnight.getStartTime());

        AiParseVO noon = fallback.parseFallback("明天中午12点");
        assertEquals("12:00", noon.getStartTime());
    }

    @Test
    @DisplayName("非法输入不抛异常：无法识别 → error=无法解析；越界日期/时间被忽略")
    void parseInvalidInputReturnsErrorInsteadOfThrowing() {
        AiParseVO nonsense = fallback.parseFallback("随便说点什么");
        assertEquals(AiConstants.PARSE_ERROR, nonsense.getError());
        assertTrue(nonsense.isEnabled());

        // 13 月 1 日与 25 点都应被静默忽略，最终判为无法解析
        AiParseVO invalid = fallback.parseFallback("13月1日 25点");
        assertEquals(AiConstants.PARSE_ERROR, invalid.getError());
        assertNull(invalid.getDate());
        assertNull(invalid.getStartTime());

        // 只有日期没有时段：不算无法解析，但时段为空
        AiParseVO dateOnly = fallback.parseFallback("明天去上课");
        assertNull(dateOnly.getError());
        assertNull(dateOnly.getStartTime());
    }

    @Test
    @DisplayName("人数与教室类型：40 个人 / 机房 / 实验室 / 多媒体，未命中返回 null")
    void parseCapacityAndRoomType() {
        AiParseVO people = fallback.parseFallback("40个人");
        assertEquals(40, people.getCapacity());

        assertEquals(AiConstants.ROOM_TYPE_COMPUTER, fallback.parseFallback("找个机房").getRoomType());
        assertEquals(AiConstants.ROOM_TYPE_LAB, fallback.parseFallback("找个实验室").getRoomType());
        assertEquals(AiConstants.ROOM_TYPE_NORMAL, fallback.parseFallback("找个多媒体教室").getRoomType());
        assertNull(fallback.parseFallback("找个地方").getRoomType());
    }
}
