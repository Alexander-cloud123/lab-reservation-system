package com.example.reservation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Redis 加分项配置属性（读 application.yml redis.* 段）
 * 对应需求设计文档 2.2 第 145 行可选加分项：缓存热门教室数据、存储登录 Token。
 * 风格对齐 AI 模块 AiProperties：总开关 + 参数全部可配置，禁止硬编码。
 *
 * @author hyr
 */
@Data
@Component
@ConfigurationProperties(prefix = "redis")
public class RedisProperties {

    /**
     * Redis 总开关（默认 true）。仅明确置为 false 时采用无状态 JWT：此模式不能立即服务端撤销会话；
     * 开启但连接异常时普通缓存回源，会话校验 fail closed，不退化为仅 JWT。
     */
    private boolean enable = true;

    /** 登录 Token 相关配置 */
    private Token token = new Token();

    /** 业务缓存相关配置 */
    private Cache cache = new Cache();

    @Data
    public static class Token {
        /** 登录会话 Key 前缀，完整 Key = keyPrefix + userId（单点会话：同用户新登录覆盖旧 Token） */
        private String keyPrefix = "auth:token:";
    }

    @Data
    public static class Cache {
        /** 数据看板聚合统计缓存 TTL（秒）：结果变化慢，预约/教室变更时主动失效 */
        private long statsTtlSeconds = 60;

        /** 教室列表缓存 TTL（秒）：含「今日剩余时段」等动态数据，只做短缓存，不做长缓存 */
        private long classroomTtlSeconds = 60;
    }
}
