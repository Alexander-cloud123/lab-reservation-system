package com.example.reservation.vo;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预约视图对象（R3：我的预约 / 学生端展示）
 * 附带关联教室展示字段（名称/楼栋/编号），不含敏感信息
 *
 * @author reservation-team
 */
@Data
public class ReservationVO {

    /** 主键 ID */
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

    /** 状态：0-待审核，1-已通过，2-已驳回，3-已取消 */
    private Integer status;

    /** 审核备注 */
    private String auditRemark;

    /** 创建时间 */
    private LocalDateTime createTime;
}
