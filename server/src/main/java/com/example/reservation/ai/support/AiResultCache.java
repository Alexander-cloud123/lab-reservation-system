package com.example.reservation.ai.support;

import com.example.reservation.config.RedisCache;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 结果缓存（复用既有 {@link RedisCache}，不重复实现 Redis 逻辑）
 *
 * 动机：上游额度是稀缺资源（该账号硬上限约 10 次/分钟，yml 注释实测留档），
 * 而合规校验、推荐、问答存在大量重复输入，每次都打上游属白耗额度。
 *
 * Key 形态：{@code cache:ai:{namespace}:{version}:{fingerprint}}（与既有 cache:stats: / cache:classroom:list: 同族命名）
 *  - {@code version} 由调用方拼装「Prompt 版本 + 生效模型（+ 合规关键词库）」的短哈希，
 *    配置一变 Key 即变，旧缓存自然不再命中——因此无需挂代次失效；
 *  - {@code fingerprint} 为输入指纹（compliance 用 purpose；recommend 用 userId+目标日期；chat 用 userId+问题）。
 *
 * 降级纪律：读写一律委托 {@link RedisCache}，其已实现「redis.enable=false 或 Redis 异常时读回源、写空转、绝不抛异常」，
 * 且反序列化失败时顺手清除脏 Key——AI 链路不得因缓存故障而阻断。
 *
 * @author reservation-team
 */
@Slf4j
@Component
public class AiResultCache {

    /** Key 前缀（完整 Key = 前缀 + namespace + ":" + version + ":" + fingerprint） */
    public static final String KEY_PREFIX = "cache:ai:";

    /** Redis 统一封装（会话 / 业务缓存 / 代次失效 / 降级纪律） */
    @Resource
    private RedisCache redisCache;

    /**
     * 组装缓存 Key
     *
     * @param namespace   业务命名空间（见 AiConstants.AI_CACHE_NS_*）
     * @param version     版本段（Prompt 版本 + 模型等配置哈希）
     * @param fingerprint 输入指纹
     */
    public String buildKey(String namespace, String version, String fingerprint) {
        return KEY_PREFIX + namespace + ":" + version + ":" + fingerprint;
    }

    /**
     * 读取缓存结果
     *
     * @return 命中的结果对象；未命中 / Redis 未启用 / Redis 异常 / 反序列化失败均返回 null（调用方回源调模型）
     */
    public <T> T get(String key, TypeReference<T> type) {
        return redisCache.getObject(key, type);
    }

    /**
     * 写入缓存结果并设置 TTL；异常空转，不影响主流程
     */
    public void put(String key, Object value, long ttlSeconds) {
        redisCache.setObject(key, value, ttlSeconds);
    }
}