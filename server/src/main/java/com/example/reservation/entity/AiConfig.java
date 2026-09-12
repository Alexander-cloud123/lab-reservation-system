package com.example.reservation.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 配置实体（ai_config）
 * 存储合规关键词、Prompt 模板等配置，避免硬编码，可动态调整（第 7 轮 AI 模块使用）
 *
 * @author reservation-team
 */
@Data
@TableName("ai_config")
public class AiConfig {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键（唯一） */
    private String configKey;

    /** 配置值 */
    private String configValue;

    /** 配置说明 */
    private String description;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间（自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
