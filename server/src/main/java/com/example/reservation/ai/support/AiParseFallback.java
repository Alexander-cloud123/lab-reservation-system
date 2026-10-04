package com.example.reservation.ai.support;

import cn.hutool.core.util.StrUtil;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiParseVO;
import com.example.reservation.common.Constants;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然语言预约解析——本地规则降级（spec.md 6.3 降级兜底必实现）
 * 大模型超时/报错/限流/密钥缺失或输出非法时，用正则 + 关键词模板识别日期/时段/人数/类型/用途，
 * 识别失败返回 error=「无法解析」的 VO（不抛异常），保证降级链在任何外部输入下都不 500。
 *
 * 说明：本类由原 AiFallbackEngine 拆分而来（按接口职责一分为四，降低单类复杂度）。
 *
 * @author reservation-team
 */
@Component
public class AiParseFallback {

    /** HH:mm 形式时间（14:00 / 14：00） */
    private static final Pattern P_TIME_DIGIT = Pattern.compile("(\\d{1,2})[:：](\\d{2})");
    /** 中文时间（下午2点 / 2点半 / 14点） */
    private static final Pattern P_TIME_CN = Pattern.compile("(上午|下午|晚上|中午)?(\\d{1,2})点(半|一刻|三刻|45|30)?");
    /** 数字日期（2026-09-12 / 2026年9月12日） */
    private static final Pattern P_DATE_FULL = Pattern.compile("(\\d{4})[年-](\\d{1,2})[月-](\\d{1,2})日?");
    /** 月日（9月12日） */
    private static final Pattern P_DATE_MD = Pattern.compile("(\\d{1,2})月(\\d{1,2})日");
    /** 周X（周一 / 周天 / 周日） */
    private static final Pattern P_DATE_WEEK = Pattern.compile("周([一二三四五六日天])");
    /** 人数（40人 / 40个人） */
    private static final Pattern P_CAPACITY = Pattern.compile("(\\d{1,4})\\s*[个]?人");
    /** 用途关键词（命中即取，优先级从高到低） */
    private static final String[] PURPOSE_KEYWORDS = {
            "课程设计", "毕业设计", "实验", "自习", "考试", "答辩", "讲座", "培训", "竞赛", "会议", "上课", "实训"
    };
    /** 时段跨度默认值（分钟）：仅识别到开始时间时，默认预约 2 小时 */
    private static final int DEFAULT_SPAN_MINUTES = 120;
    /** 每日可预约最晚结束分钟数（N4：由 Constants.DAILY_SLOT_END 派生，单一来源） */
    private static final int MAX_END_MINUTES = Constants.DAILY_SLOT_END.getHour() * 60;

    /**
     * 解析降级：正则 + 关键词模板识别日期/时段/人数/类型/用途
     * 识别失败时返回 error=「无法解析」的 VO（不抛异常）
     */
    public AiParseVO parseFallback(String text) {
        AiParseVO vo = new AiParseVO();
        vo.setEnabled(true);
        vo.setDate(parseDate(text));
        int[] slot = parseTimeSlot(text);
        if (slot != null) {
            vo.setStartTime(formatMinute(slot[0]));
            vo.setEndTime(formatMinute(slot[1]));
        }
        vo.setCapacity(parseCapacity(text));
        vo.setRoomType(parseRoomType(text));
        vo.setPurpose(parsePurpose(text));
        // 日期/时段均未识别出来 → 判定无法解析（前端提示用户重新描述）
        if (StrUtil.isBlank(vo.getDate()) && slot == null) {
            vo.setError(AiConstants.PARSE_ERROR);
        }
        return vo;
    }

    /** 日期解析：今天/明天/后天/周X/指定日期/月日，缺省 null */
    private String parseDate(String text) {
        if (text.contains("明天")) {
            return LocalDate.now().plusDays(1).toString();
        }
        if (text.contains("后天")) {
            return LocalDate.now().plusDays(2).toString();
        }
        if (text.contains("今天") || text.contains("今晚") || text.contains("当日")) {
            return LocalDate.now().toString();
        }
        Matcher m = P_DATE_WEEK.matcher(text);
        if (m.find()) {
            DayOfWeek target = weekNameToDay(m.group(1));
            if (target != null) {
                return nextWeekday(target).toString();
            }
        }
        m = P_DATE_FULL.matcher(text);
        if (m.find()) {
            LocalDate date = safeDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            if (date != null) {
                return date.toString();
            }
        }
        m = P_DATE_MD.matcher(text);
        if (m.find()) {
            LocalDate date = safeDate(LocalDate.now().getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            if (date != null) {
                return date.toString();
            }
        }
        return null;
    }

    /**
     * 构造日期（M6 修复：月份/日越界如"13月1日""2月30日"捕获返回 null，识别失败走"无法解析"，
     * 保证 AI 降级链在任何外部输入下都不抛 500——降级链的意义就是任何情况都不 500）
     */
    private LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** 周中文名 → DayOfWeek（周天/周日 → SUNDAY） */
    private DayOfWeek weekNameToDay(String cn) {
        return switch (cn) {
            case "一" -> DayOfWeek.MONDAY;
            case "二" -> DayOfWeek.TUESDAY;
            case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY;
            case "五" -> DayOfWeek.FRIDAY;
            case "六" -> DayOfWeek.SATURDAY;
            case "日", "天" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    /** 下一个周 X（不含今天） */
    private LocalDate nextWeekday(DayOfWeek target) {
        LocalDate today = LocalDate.now();
        int diff = (target.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        if (diff == 0) {
            diff = 7;
        }
        return today.plusDays(diff);
    }

    /**
     * 时间段解析：识别文本中所有时间点，取首尾为 [开始, 结束]；
     * 仅识别到开始时间时默认 +2 小时（截断至 22:00）
     */
    private int[] parseTimeSlot(String text) {
        List<Integer> minutes = new ArrayList<>();
        Matcher m = P_TIME_DIGIT.matcher(text);
        while (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            if (h >= 0 && h <= 23 && min <= 59) {
                minutes.add(h * 60 + min);
            }
        }
        m = P_TIME_CN.matcher(text);
        while (m.find()) {
            String period = m.group(1);
            int h = Integer.parseInt(m.group(2));
            if (h < 0 || h > 24) {
                continue;
            }
            int min = 0;
            String suffix = m.group(3);
            if ("半".equals(suffix) || "30".equals(suffix)) {
                min = 30;
            } else if ("一刻".equals(suffix)) {
                min = 15;
            } else if ("三刻".equals(suffix) || "45".equals(suffix)) {
                min = 45;
            }
            // 12 小时制转 24 小时制：上午/下午/晚上 前缀处理
            if ("下午".equals(period) || "晚上".equals(period)) {
                h = (h == 12) ? 12 : h + 12;
            } else if ("上午".equals(period) && h == 12) {
                h = 0;
            } else if ("中午".equals(period)) {
                h = 12;
            }
            minutes.add(h * 60 + min);
        }
        if (minutes.isEmpty()) {
            return null;
        }
        int start = minutes.get(0);
        int end = minutes.get(minutes.size() - 1);
        if (end <= start) {
            end = start + DEFAULT_SPAN_MINUTES;
        }
        end = Math.min(end, MAX_END_MINUTES);
        start = Math.min(start, MAX_END_MINUTES - 60);
        return new int[]{start, end};
    }

    /** 人数解析：N人 / N个人，缺省 null */
    private Integer parseCapacity(String text) {
        Matcher m = P_CAPACITY.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return null;
    }

    /** 教室类型解析：机房/实验室/普通教室，未命中 null */
    private String parseRoomType(String text) {
        if (text.contains("机房") || text.contains("电脑") || text.contains("机位")) {
            return AiConstants.ROOM_TYPE_COMPUTER;
        }
        if (text.contains("实验室") || text.contains("实验")) {
            return AiConstants.ROOM_TYPE_LAB;
        }
        if (text.contains("普通") || text.contains("多媒体") || text.contains("教室")) {
            return AiConstants.ROOM_TYPE_NORMAL;
        }
        return null;
    }

    /** 用途解析：命中用途关键词取第一个，未命中 null */
    private String parsePurpose(String text) {
        for (String kw : PURPOSE_KEYWORDS) {
            if (text.contains(kw)) {
                return kw;
            }
        }
        return null;
    }

    private String formatMinute(int minute) {
        return String.format("%02d:%02d", minute / 60, minute % 60);
    }
}