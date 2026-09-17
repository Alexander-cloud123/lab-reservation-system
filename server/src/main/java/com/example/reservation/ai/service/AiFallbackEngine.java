package com.example.reservation.ai.service;

import cn.hutool.core.util.StrUtil;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiComplianceVO;
import com.example.reservation.ai.dto.AiRecommendItemVO;
import com.example.reservation.ai.dto.AiParseVO;
import com.example.reservation.entity.Classroom;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 降级规则引擎（R7，spec.md 6.3 降级兜底必实现）
 * 大模型调用超时/报错/限流/密钥缺失时，自动切换本地规则模拟模式，不阻断业务：
 *  - 解析：正则 + 关键词模板（覆盖「明天下午2点 40人 机房」等常用句式）
 *  - 推荐：按用户历史偏好 + 明日空闲状态规则打分排序 Top3
 *  - 问答：关键词匹配 FAQ 库
 *  - 合规：ai_config.compliance_keywords 本地违规关键词库匹配
 *
 * @author reservation-team
 */
@Component
public class AiFallbackEngine {

    /* ===== 自然语言解析（正则 + 关键词模板）===== */

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
    /** 每日可预约最晚结束分钟数（22:00） */
    private static final int MAX_END_MINUTES = 22 * 60;

    /* ===== 场景限定问答 FAQ 库 ===== */
    private static final Map<String, String> FAQ_RULES = buildFaqRules();

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

    /* ===== 智能推荐降级（规则打分排序，spec.md 6.3）===== */

    /** 用户历史偏好（由推荐 Service 从预约记录 + 教室维度统计） */
    public record UserPreference(String preferredBuilding, Integer preferredType, String preferredTimeSlot, int avgCapacity) {
    }

    /** 推荐候选（教室 + 明日空闲特征） */
    public record RecommendCandidate(Classroom classroom, boolean fullyFree, int occupiedCount) {
    }

    /**
     * 规则打分排序：空闲度(10) > 楼栋偏好(5) > 类型偏好(4) > 容量匹配(3) > 历史活跃(1)
     * 返回按分数降序的 Top N（附一句话推荐理由）
     */
    public List<AiRecommendItemVO> rankAndTop(List<RecommendCandidate> candidates, UserPreference pref, int topCount) {
        // 注意：stream().toList() 返回不可变列表，必须先收集为可变列表再排序
        List<Scored> scored = candidates.stream().map(c -> score(c, pref)).collect(java.util.stream.Collectors.toList());
        scored.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(c -> c.candidate().classroom().getId()));
        return scored.stream().limit(Math.max(topCount, 0)).map(s -> {
            AiRecommendItemVO item = new AiRecommendItemVO();
            Classroom c = s.candidate().classroom();
            item.setClassroomId(c.getId());
            item.setName(c.getName());
            item.setBuilding(c.getBuilding());
            item.setRoomNo(c.getRoomNo());
            item.setType(c.getType());
            item.setCapacity(c.getCapacity());
            item.setReason(buildReason(s, pref));
            return item;
        }).toList();
    }

    /** 单候选打分（空闲度 10 / 楼栋 5 / 类型 4 / 容量 3 / 活跃 1） */
    private Scored score(RecommendCandidate candidate, UserPreference pref) {
        Classroom c = candidate.classroom();
        int score = 0;
        // 空闲度：明日完全空闲优先（推荐核心维度）
        score += candidate.fullyFree() ? 10 : 5;
        // 楼栋偏好
        if (pref.preferredBuilding() != null && pref.preferredBuilding().equals(c.getBuilding())) {
            score += 5;
        }
        // 类型偏好
        if (pref.preferredType() != null && pref.preferredType().equals(c.getType())) {
            score += 4;
        }
        // 容量匹配：历史平均容量的 0.8~1.5 倍区间内
        if (pref.avgCapacity() > 0) {
            double lo = pref.avgCapacity() * 0.8;
            double hi = pref.avgCapacity() * 1.5;
            if (c.getCapacity() >= lo && c.getCapacity() <= hi) {
                score += 3;
            }
        }
        // 历史活跃：被约次数越多越优先（趋同偏好）
        score += Math.min(candidate.occupiedCount(), 5);
        return new Scored(candidate, score);
    }

    /** 生成一句话推荐理由（降级模式，按命中维度组合） */
    private String buildReason(Scored s, UserPreference pref) {
        Classroom c = s.candidate().classroom();
        List<String> parts = new ArrayList<>();
        parts.add(s.candidate().fullyFree() ? "明日全天空闲" : "明日仍有空闲时段");
        if (pref.preferredBuilding() != null && pref.preferredBuilding().equals(c.getBuilding())) {
            parts.add("您常约的" + c.getBuilding());
        }
        if (pref.preferredType() != null && pref.preferredType().equals(c.getType())) {
            parts.add("符合您常约的类型");
        }
        if (pref.avgCapacity() > 0 && c.getCapacity() >= pref.avgCapacity() * 0.8 && c.getCapacity() <= pref.avgCapacity() * 1.5) {
            parts.add("容量接近您的常用规模");
        }
        return String.join("，", parts);
    }

    /** 打分中间载体 */
    private record Scored(RecommendCandidate candidate, int score) {
    }

    /* ===== 场景限定问答降级（关键词 FAQ 库，spec.md 6.3）===== */

    private static Map<String, String> buildFaqRules() {
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("取消", "取消预约规则：待审核或已通过的预约可在预约开始前 1 小时自由取消（在我的预约页点击取消，需二次确认）；开始前不足 1 小时不可取消，如有特殊情况请联系管理员处理。");
        rules.put("冲突", "预约冲突规则：同一教室同一日期下，新预约时段与已通过预约时段存在重叠即判定冲突（前后端双重校验）。选择时段时系统会实时提示，冲突时无法提交。");
        rules.put("审核", "审核流程：学生提交预约后状态为待审核，管理员在预约审核页通过或驳回（驳回会填写审核备注）；审核结果可在我的预约页查看，消息通知中心也会同步提示。");
        rules.put("状态", "预约状态：待审核（提交后）、已通过（管理员通过）、已驳回（管理员驳回，不可修改）、已取消（用户取消）。");
        rules.put("怎么预约", "预约步骤：教室列表 → 点击教室卡片进入详情 → 选择日期与时段 → 填写用途 → 提交预约（系统实时做冲突校验）。也可在教室列表页使用「AI 快速预约」直接描述需求。");
        rules.put("如何预约", "预约步骤：教室列表 → 点击教室卡片进入详情 → 选择日期与时段 → 填写用途 → 提交预约（系统实时做冲突校验）。也可在教室列表页使用「AI 快速预约」直接描述需求。");
        rules.put("我的", "个人预约记录：在我的预约页可按状态（待审核/已通过/已驳回/已取消）查看全部记录，今日预约自动置顶；个人中心可查看累计预约次数、本月预约数与通过率。");
        rules.put("教室", "教室信息：教室列表支持按关键词/楼栋/类型/日期筛选，卡片展示实时状态（当前空闲/使用中/已结束）与容量、设备；进入详情可查看当日时段占用情况并直接预约。");
        rules.put("时间", "可预约时段：每日 08:00-22:00（14 小时），结束时间不能晚于 22:00。");
        return rules;
    }

    /** 问答降级：关键词命中 FAQ 库，未命中返回场景外预设话术 */
    public String chatFallback(String question) {
        for (Map.Entry<String, String> rule : FAQ_RULES.entrySet()) {
            if (question.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        return AiConstants.CHAT_OUT_OF_SCOPE;
    }

    /* ===== 合规校验降级（ai_config.compliance_keywords 关键词规则，spec.md 6.3）===== */

    /**
     * 合规降级：遍历本地违规关键词，命中 → 不合规（附命中关键词）；未命中 → 合规
     */
    public AiComplianceVO complianceFallback(String purpose, List<String> keywords) {
        AiComplianceVO vo = new AiComplianceVO();
        vo.setEnabled(true);
        for (String kw : keywords) {
            if (purpose.contains(kw)) {
                vo.setCompliant(false);
                vo.setReason(AiConstants.COMPLIANCE_HIT_PREFIX + kw);
                return vo;
            }
        }
        vo.setCompliant(true);
        vo.setReason(AiConstants.COMPLIANCE_OK_REASON);
        return vo;
    }
}
