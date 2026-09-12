package com.example.reservation.vo;

import lombok.Data;

import java.util.List;

/**
 * 学生端教室视图对象（R3 教室浏览）
 * 含实时状态标签与指定日期已通过预约占用时段
 *
 * @author reservation-team
 */
@Data
public class ClassroomVO {

    /** 主键 ID */
    private Long id;

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

    /** 设备说明 */
    private String equipment;

    /** 备注描述 */
    private String description;

    /** 状态：0-停用，1-可用（学生端列表仅返回可用教室） */
    private Integer status;

    /** 实时状态标签：当前空闲 / 使用中（口径见 ClassroomService 实现注释） */
    private String statusLabel;

    /** 指定日期（默认当天）该教室已通过预约占用时段 */
    private List<OccupiedSlotVO> occupiedSlots;
}
