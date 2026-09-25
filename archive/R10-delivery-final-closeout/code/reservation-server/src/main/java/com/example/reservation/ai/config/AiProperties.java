package com.example.reservation.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 模块配置属性（R7，读 application.yml ai.* 段）
 * 字段与 spec.md 2.3 模板一致：enable / base-url / api-key / model / timeout-seconds / max-tokens / rpm-limit
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

    /** 大模型调用超时（秒，读取超时；实测模型响应可达数十秒，默认 60s） */
    private int timeoutSeconds;

    /** 限流保护（次/分钟，RPM≈20，spec.md 6.1） */
    private int rpmLimit;

    /** 全站聚合限流（次/分钟，N2：默认取 rpmLimit × 5；超限直接返回繁忙提示，不触发对外调用） */
    private int globalRpmLimit;

    /** 单次响应最大 Token 数（防止长输出打满默认 4096 导致超时/乱码，默认 1024，≤0 时不限制） */
    private int maxTokens = 1024;
}
