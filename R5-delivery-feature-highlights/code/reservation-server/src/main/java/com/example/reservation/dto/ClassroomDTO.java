package com.example.reservation.dto;

import lombok.Data;

/**
 * 教室新增/编辑请求参数（编辑时携带 id）
 * 校验规则（spec.md 7 节 R2）：名称/楼栋/编号/类型/容量必填，容量 > 0
 *
 * @author reservation-team
 */
@Data
public class ClassroomDTO {

    /** 教室 ID（编辑时必填） */
    private Long id;

    /** 教室名称（必填） */
    private String name;

    /** 所属楼栋（必填） */
    private String building;

    /** 教室编号（必填） */
    private String roomNo;

    /** 类型（必填）：1-普通教室，2-实验室，3-机房 */
    private Integer type;

    /** 容纳人数（必填，>0） */
    private Integer capacity;

    /** 设备说明（选填） */
    private String equipment;

    /** 备注描述（选填） */
    private String description;
}
