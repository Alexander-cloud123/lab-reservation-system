package com.example.reservation.ai.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 预约意图识别（R15-1 对话内预约规划）——纯字符串规则，不调模型。
 *
 * <p>只做「该不该进入预约规划」的闸门：命中后由 {@code AiBookingPlanService} 解析需求、
 * 检索并给出可确认的方案；不命中则走普通问答。宁可少判（用户可换个说法），
 * 也不在「查询/规则」类问题上误触发（避免给出与问题无关的方案卡）。
 *
 * <p>规则：必须同时命中「请求动作词」与「预约线索词」，且不含「查询/规则类」词。
 * R15-2 另提供 {@link #mentionsEndTime(String)}：判断用户是否**明确说了结束时间**，
 * 避免模型自行补一个结束时间后，方案卡把「估算」伪装成用户的真实需求。
 *
 * @author hyr
 */
public final class AiBookingIntent {

    private AiBookingIntent() {
    }

    /** 请求动作：明确表达「请你帮我做」的动作词（不含「怎么/如何」这类咨询式说法） */
    private static final String[] REQUEST_CUES = {
            "帮我", "给我", "替我", "帮忙", "麻烦你",
            "想约", "要约", "想订", "要订", "想借",
            "找一间", "找间", "约一间", "订一间", "预约一间", "预约个",
            "能约", "还能约", "有空吗", "有空的", "空着吗"
    };

    /** 预约线索：动作对象/时间/时段等要素，缺一不可（防「帮我查个数据」误判） */
    private static final String[] BOOKING_CUES = {
            "预约", "约", "订", "借", "占",
            "教室", "机房", "实验室", "机位",
            "今天", "明天", "后天", "上午", "下午", "晚上", "点", "时段"
    };

    /** 查询/规则类：这些说法是在「问」而不是「办」，不进规划分支 */
    private static final String[] QUERY_CUES = {
            "有哪些", "有没有", "查一下", "查看", "看看", "记录", "状态", "历史",
            "怎么", "如何", "为什么", "规则", "流程", "要多久", "需要多久", "多久"
    };

    /** 时间点：「下午两点」「14 点」「晚上七点半」 */
    private static final Pattern TIME_TOKEN =
            Pattern.compile("(上午|下午|晚上|中午)?\\s*(\\d{1,2}|十[一二]?|[两二三四五六七八九])\\s*点");

    /** 时段连接词：「到 / 至 / - 」——配合两个时间点即「14:00 到 16:00」 */
    private static final Pattern TIME_RANGE =
            Pattern.compile("(到|至|—|~|-)\\s*(上午|下午|晚上|中午)?\\s*(\\d{1,2}|十[一二]?|[两二三四五六七八九])\\s*点");

    /** 时长表达：「两个小时」「3 小时」「一小时」「钟头」——等价于明确给了结束时间 */
    private static final Pattern DURATION =
            Pattern.compile("(\\d{1,2}|[两二三四五六七八九十])\\s*(个)?\\s*(小时|钟头)");

    /**
     * 是否为「请帮我预约」类意图
     *
     * @param question 用户问题（可为空）
     * @return true=进入预约规划分支
     */
    public static boolean matches(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        for (String cue : QUERY_CUES) {
            if (question.contains(cue)) {
                return false;
            }
        }
        return containsAny(question, REQUEST_CUES) && containsAny(question, BOOKING_CUES);
    }

    /**
     * 用户是否明确说了结束时间（R15-2）
     *
     * <p>模型解析有时会自行补「结束 = 开始 + 1 小时」，这对用户是隐性的假设；
     * 这里以用户原文为准：出现两个时间点、时段连接词，或直接说了时长，才算明确。
     * 不明确时方案卡改为「1/2/3 小时可选档」，让用户自己挑。
     *
     * @param question 用户原文（可为空）
     * @return true=用户已经说了结束时间（或等效的时长）
     */
    public static boolean mentionsEndTime(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        if (DURATION.matcher(question).find()) {
            return true;
        }
        Matcher token = TIME_TOKEN.matcher(question);
        if (countMatches(token) >= 2) {
            return true;
        }
        return TIME_RANGE.matcher(question).find();
    }

    private static int countMatches(Matcher matcher) {
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean containsAny(String text, String[] cues) {
        for (String cue : cues) {
            if (text.contains(cue)) {
                return true;
            }
        }
        return false;
    }
}
