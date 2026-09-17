package com.example.reservation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置（需求设计文档 2.2 第 145 行可选加分项）
 * 1. 显式声明 RedisTemplate：Key/Value（含 Hash Key/Value）统一 String 序列化，
 *    避免 JDK 默认二进制序列化在 redis-cli 中出现乱码，Value 中的业务对象由 RedisCache 以 JSON 文本存取；
 * 2. 本类不提供 ObjectMapper Bean：缓存与 Spring MVC 共用 Spring Boot 自动配置的主 ObjectMapper。
 *    LocalDate 输出 ISO 字符串（如 "2026-09-18"）来自 Spring Boot JacksonAutoConfiguration 的特性
 *    默认值（FEATURE_DEFAULTS 禁用 WRITE_DATES_AS_TIMESTAMPS），与 yml 无关；此前本类自建 mapper
 *    会顶掉该自动配置，导致上述默认值与 spring.jackson.* 双双失效（表现为日期输出数组 [2026,9,18]）。
 *    application.yml 的 spring.jackson.* 现已对全站（含 Redis 缓存读写）真实生效。
 * 使用 Spring Boot 默认 Lettuce 客户端，禁止引入 Redisson 等额外组件（AGENTS.md 第 7 节红线）。
 *
 * @author reservation-team
 */
@Configuration
public class RedisConfig {

    /**
     * 字符串模板：Key、Value 均为可读字符串（业务对象 JSON 化后作为字符串 Value 存储）
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        // Key / HashKey / Value / HashValue 全部使用字符串序列化，杜绝乱码
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        template.setStringSerializer(stringSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
