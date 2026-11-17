package com.example.reservation.ai.dto;

import lombok.Data;

/**
 * 预约方案中的单个候选教室（R15-1 对话内预约规划）
 *
 * <p>只读结果：仅描述「这间教室在该时段可预约」，不承载任何写操作；
 * 用户点「去预约」后仍进入既有确认弹窗，由用户手动提交并再次校验冲突。
 *
 * @author hyr
 */
@Data
public class AiPlanCandidateVO {

    /** 教室 ID（前端据此打开确认弹窗） */
    private Long classroomId;

    /** 教室名称 */
    private String name;

    /** 所属楼栋 */
    private String building;

    /** 教室编号 */
    private String roomNo;

    /** 类型：1-普通教室，2-实验室，3-机房 */
    private Integer type;

    /** 容纳人数 */
    private Integer capacity;

    /** 推荐理由（模板生成，一句话） */
    private String reason;
}
