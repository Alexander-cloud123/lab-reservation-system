package com.example.reservation.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置（需求设计文档 2.2 第 145 行可选加分项）
 * 1. 显式声明 RedisTemplate：Key/Value（含 Hash Key/Value）统一 String 序列化，
 *    避免 JDK 默认二进制序列化在 redis-cli 中出现乱码，Value 中的业务对象由 RedisCache 以 JSON 文本存取；
 * 2. 提供缓存专用 ObjectMapper（注册 JSR-310 时间模块）。
 *    注意：本项目仅此一个 ObjectMapper Bean，它实际承担 Spring MVC 的 HTTP JSON 序列化
 *    （其存在使 Spring Boot 的 Jackson 自动配置按 @ConditionalOnMissingBean 不再生效，
 *    application.yml 的 spring.jackson.* 不参与实际序列化），因此必须在此禁用
 *    WRITE_DATES_AS_TIMESTAMPS，保证 LocalDate 输出 ISO 字符串（如 "2026-09-18"）
 *    而非数组 [2026,9,18]，否则前端日历事件按字符串拼接日期会得到 Invalid Date。
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

    /**
     * 缓存专用 Jackson 映射器：注册 Java 8 时间模块；反序列化容忍未知字段（前后端/VO 演进不击穿缓存）
     */
    @Bean("cacheObjectMapper")
    public ObjectMapper cacheObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 日期时间序列化为 ISO 字符串（LocalDate → "2026-09-18"），禁用 [2026,9,18] 数组格式；
        // Redis 缓存读写共用此 mapper，格式内部自洽，反序列化不受影响
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
