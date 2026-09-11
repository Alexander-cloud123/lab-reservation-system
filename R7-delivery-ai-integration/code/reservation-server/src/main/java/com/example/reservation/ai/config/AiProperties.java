package com.example.reservation.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 模块配置属性（R7，读 application.yml ai.* 段）
 * 字段与 spec.md 2.3 模板一致：enable / base-url / api-key / model / timeout-seconds / rpm-limit
 * 密钥仅从环境变量 AGNES_API_KEY 注入（application.yml 中 ${AGNES_API_KEY:}），禁止硬编码
 *
 * @author reservation-team
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** AI 总开关（静态开关，默认 false；与 ai_config.ai_enable 组成双开关，任一为 false 即关闭） */
    private boolean enable;

    /** Agnes AI 服务地址（兼容 OpenAI v1 规范） */
    private String baseUrl;

    /** API 密钥（来自环境变量 AGNES_API_KEY，前端零接触） */
    private String apiKey;

    /** 默认模型（ai_config.ai_model 可覆盖） */
    private String model;

    /** 大模型调用超时（秒，需求文档 1.5：AI 接口响应 ≤3s） */
    private int timeoutSeconds;

    /** 限流保护（次/分钟，RPM≈20，spec.md 6.1） */
    private int rpmLimit;
}
