package com.example.reservation.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 预约实体（reservation）
 * 状态流转（禁止简化）：待审核0 → 已通过1/已驳回2；待审核0/已通过1 → 已取消3
 *
 * @author reservation-team
 */
@Data
@TableName("reservation")
public class Reservation {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 预约用户 ID */
    private Long userId;

    /** 预约教室 ID */
    private Long classroomId;

    /** 预约日期 */
    private LocalDate reserveDate;

    /** 开始时间 */
    private LocalTime startTime;

    /** 结束时间 */
    private LocalTime endTime;

    /** 预约用途 */
    private String purpose;

    /** 状态：0-待审核，1-已通过，2-已驳回，3-已取消 */
    private Integer status;

    /** 审核备注 */
    private String auditRemark;

    /** 审核人 ID */
    private Long auditorId;

    /** 审核时间 */
    private LocalDateTime auditTime;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间（自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
