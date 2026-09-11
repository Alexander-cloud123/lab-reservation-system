package com.example.reservation.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教室实体（classroom）
 * type：1-普通教室，2-实验室，3-机房；status：0-停用，1-可用
 *
 * @author reservation-team
 */
@Data
@TableName("classroom")
public class Classroom {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
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

    /** 状态：0-停用，1-可用 */
    private Integer status;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间（自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
