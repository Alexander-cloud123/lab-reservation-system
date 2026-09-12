package com.example.reservation.ai.dto;

import lombok.Data;

/**
 * 智能推荐单条教室项（含推荐理由，一句话）
 *
 * @author reservation-team
 */
@Data
public class AiRecommendItemVO {

    /** 教室 ID */
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

    /** 推荐理由（一句话，供前端展示） */
    private String reason;
}
