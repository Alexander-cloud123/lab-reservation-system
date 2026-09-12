package com.example.reservation.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 冲突检测结果（R3 预约核心）
 *
 * @author reservation-team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConflictVO {

    /** 是否冲突 */
    private Boolean conflict;

    /** 冲突原因（不冲突时为可预约提示） */
    private String reason;
}
