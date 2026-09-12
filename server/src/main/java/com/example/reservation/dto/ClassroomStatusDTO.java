package com.example.reservation.dto;

import lombok.Data;

/**
 * 教室启用/停用请求参数
 *
 * @author reservation-team
 */
@Data
public class ClassroomStatusDTO {

    /** 目标状态：0-停用，1-可用 */
    private Integer status;
}
