package com.example.reservation.dto;

import lombok.Data;

/**
 * 用户启用/禁用请求参数
 *
 * @author reservation-team
 */
@Data
public class UserStatusDTO {

    /** 目标状态：0-禁用，1-正常 */
    private Integer status;
}
