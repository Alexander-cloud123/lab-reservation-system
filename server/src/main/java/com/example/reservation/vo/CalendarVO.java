package com.example.reservation.vo;

import lombok.Data;

import java.time.LocalDate;

/**
 * 日历总览视图对象（R5 亮点功能）
 * 月/周视图色块数据：预约时间段 + 状态 + 教室展示字段；
 * 状态与 R3/R4 常量一致（0-待审核，1-已通过，2-已驳回，3-已取消）
 *
 * @author reservation-team
 */
@Data
public class CalendarVO {

    /** 预约 ID */
    private Long id;

    /** 预约教室 ID */
    private Long classroomId;

    /** 教室名称 */
    private String classroomName;

    /** 所属楼栋 */
    private String building;

    /** 教室编号 */
    private String roomNo;

    /** 预约日期 */
    private LocalDate reserveDate;

    /** 开始时间（HH:mm） */
    private String startTime;

    /** 结束时间（HH:mm） */
    private String endTime;

    /** 预约用途 */
    private String purpose;

    /** 是否为当前登录用户本人的预约（R3：前端据此决定是否显示用途；他人且非管理员时为 null） */
    private Boolean mine;

    /** 状态：0-待审核，1-已通过，2-已驳回，3-已取消 */
    private Integer status;

    /** 审核备注 */
    private String auditRemark;
}
