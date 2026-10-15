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

    /** 流式问答（R12 SSE）相关参数 */
    private Stream stream = new Stream();

    /**
     * 流式问答参数（R12）
     * 目前仅承载 SSE 异步线程池调优项；仅承载 chat?stream=true，不与其他业务共享
     */
    @Data
    public static class Stream {

        /** SSE 异步应答线程池参数 */
        private Executor executor = new Executor();
    }

    /**
     * AI SSE 流式应答专用线程池参数
     * 有界队列 + AbortPolicy：占满时由 controller 捕获拒绝并回 error 事件，不压垮 Tomcat 请求线程
     */
    @Data
    public static class Executor {

        /** 核心线程数（常驻，默认 2） */
        private int corePoolSize = 2;

        /** 最大线程数（峰值并发，默认 8） */
        private int maxPoolSize = 8;

        /** 有界等待队列容量（默认 100，超出且线程数已达 max 时拒绝） */
        private int queueCapacity = 100;
    }
}
