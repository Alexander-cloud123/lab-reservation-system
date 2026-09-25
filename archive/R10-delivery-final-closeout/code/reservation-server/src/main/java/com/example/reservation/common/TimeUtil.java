package com.example.reservation.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 时间解析/格式化工具（R3 预约核心）
 * 统一处理前端传入的日期（yyyy-MM-dd）与时间（HH:mm / HH:mm:ss），
 * 非法格式统一抛出 400 业务异常，禁止魔法格式散落
 *
 * @author reservation-team
 */
public final class TimeUtil {

    /** 日期格式 */
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /** 时间格式（输出统一 HH:mm） */
    public static final String TIME_PATTERN = "HH:mm";

    /** 日期时间格式（输出统一 yyyy-MM-dd HH:mm:ss，R6 预约记录导出用） */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private TimeUtil() {
    }

    /**
     * 解析日期字符串（yyyy-MM-dd），非法抛出 400
     */
    public static LocalDate parseDate(String date) {
        try {
            return LocalDate.parse(date, DateTimeFormatter.ofPattern(DATE_PATTERN));
        } catch (DateTimeParseException e) {
            throw new BusinessException("日期格式不正确，应为 yyyy-MM-dd");
        }
    }

    /**
     * 解析时间字符串（HH:mm 或 HH:mm:ss），非法抛出 400
     */
    public static LocalTime parseTime(String time) {
        try {
            // ISO_LOCAL_TIME 同时兼容 HH:mm 与 HH:mm:ss
            return LocalTime.parse(time);
        } catch (DateTimeParseException e) {
            throw new BusinessException("时间格式不正确，应为 HH:mm");
        }
    }

    /**
     * 时间格式化为 HH:mm（响应输出统一口径）
     */
    public static String formatTime(LocalTime time) {
        return time.format(DateTimeFormatter.ofPattern(TIME_PATTERN));
    }

    /**
     * 日期时间格式化为 yyyy-MM-dd HH:mm:ss（导出/展示统一口径，空值返回 null）
     */
    public static String formatDateTime(LocalDateTime time) {
        return time == null ? null : time.format(DateTimeFormatter.ofPattern(DATE_TIME_PATTERN));
    }
}
