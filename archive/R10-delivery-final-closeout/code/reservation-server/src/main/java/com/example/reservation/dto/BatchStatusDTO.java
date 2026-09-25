package com.example.reservation.dto;

import lombok.Data;

import java.util.List;

/**
 * 教室批量启用/停用请求参数
 *
 * @author reservation-team
 */
@Data
public class BatchStatusDTO {

    /** 教室 ID 列表（非空） */
    private List<Long> ids;

    /** 目标状态：0-停用，1-可用 */
    private Integer status;
}
