package com.example.reservation.ai.dto;

import com.example.reservation.ai.config.AiConstants;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话内预约规划结果（R15-1）——SSE {@code plan} 事件与非流式接口共用同一 VO。
 *
 * <p>语义：AI 只给出「可确认的方案」，不提交订单。candidates 为空时由 clarify / message
 * 说明原因（解析失败 / 该时段无可用教室），前端不出「去预约」按钮。
 *
 * @author hyr
 */
@Data
public class AiPlanVO {

    /** AI 是否启用（false 时前端隐藏入口，不渲染方案卡） */
    private boolean enabled;

    /** 关闭态提示文案（enabled=false 时非空） */
    private String message;

    /** 需要用户补充信息的引导文案（解析失败 / 无候选时非空，可空） */
    private String clarify;

    /** 解析出的预约日期（yyyy-MM-dd） */
    private String date;

    /** 开始时间（HH:mm） */
    private String startTime;

    /** 结束时间（HH:mm） */
    private String endTime;

    /** 需求人数（可为空，仅用于筛选与展示） */
    private Integer capacity;

    /** 需求类型文案：普通教室 / 实验室 / 机房（可空） */
    private String roomType;

    /** 预约用途（可空，用户可在确认弹窗修改） */
    private String purpose;

    /** 候选教室（最多 3 间；无候选时为空列表而非 null） */
    private List<AiPlanCandidateVO> candidates = new ArrayList<>();

    /** 前 3 间之外仍有可用教室的数量（0 表示没有更多） */
    private int moreAvailable;

    /** 该时段因冲突/容量/类型被排除的教室数（供文案展示） */
    private int excludedCount;

    /** AI 关闭时的友好返回 */
    public static AiPlanVO disabled() {
        AiPlanVO vo = new AiPlanVO();
        vo.setEnabled(false);
        vo.setMessage(AiConstants.AI_DISABLED_MESSAGE);
        return vo;
    }
}
