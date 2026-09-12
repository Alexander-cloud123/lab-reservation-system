package com.example.reservation.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 管理端预约视图对象（R3：预约审核全量查询）
 * 在预约展示字段基础上追加用户信息与审核信息
 *
 * @author reservation-team
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ReservationManageVO extends ReservationVO {

    /** 预约用户 ID */
    private Long userId;

    /** 预约用户账号 */
    private String userAccount;

    /** 预约用户姓名 */
    private String userName;

    /** 审核人 ID */
    private Long auditorId;

    /** 审核时间 */
    private LocalDateTime auditTime;
}
