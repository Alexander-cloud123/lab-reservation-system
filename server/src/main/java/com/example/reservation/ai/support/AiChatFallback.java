package com.example.reservation.ai.support;

import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.common.Constants;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预约智能问答——本地关键词 FAQ 降级（spec.md 6.3 降级兜底必实现）
 * 大模型不可用或输出非法时，按关键词命中 FAQ 库；未命中返回场景外预设话术。
 *
 * 说明：本类由原 AiFallbackEngine 拆分而来（按接口职责一分为四，降低单类复杂度）。
 *
 * @author reservation-team
 */
@Component
public class AiChatFallback {

    /** 场景限定问答 FAQ 库（关键词 → 答复；LinkedHashMap 保证命中顺序稳定） */
    private static final Map<String, String> FAQ_RULES = buildFaqRules();

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
        rules.put("时间", "可预约时段：每日 " + Constants.DAILY_AVAILABLE_START + "-" + Constants.DAILY_AVAILABLE_END
                + "（" + Constants.DAILY_AVAILABLE_HOURS + " 小时），结束时间不能晚于 " + Constants.DAILY_AVAILABLE_END + "。");
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
}