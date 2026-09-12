package com.example.reservation.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的收藏视图对象（R4 收藏全链路 / 个人中心常用教室快捷入口）
 * 附带教室展示字段，便于前端直接渲染
 *
 * @author reservation-team
 */
@Data
public class FavoriteVO {

    /** 收藏记录 ID */
    private Long id;

    /** 教室 ID */
    private Long classroomId;

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

    /** 收藏时间 */
    private LocalDateTime createTime;
}
