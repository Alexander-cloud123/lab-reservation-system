package com.example.reservation.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Redis 业务对象缓存 / 代次失效 / 互斥锁分支补充（与 RedisCacheTest 互补，后者覆盖会话与鉴权状态）
 *
 * <p>降级纪律：禁用 Redis 时全部空操作；启用但读写异常时按「未命中/放行」处理且不向上抛异常，
 * 保证 Redis 故障不阻断核心业务。
 */
@ExtendWith(MockitoExtension.class)
class RedisCacheObjectCacheTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RedisCache redisCache;

    private RedisProperties redisProperties;

    @BeforeEach
    void setUp() {
        redisProperties = new RedisProperties();
        redisProperties.setEnable(true);
        ReflectionTestUtils.setField(redisCache, "redisProperties", redisProperties);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /* ==================== 总开关 ==================== */

    @Test
    @DisplayName("isEnabled：跟随 redis.enable")
    void isEnabled_followsProperty() {
        assertTrue(redisCache.isEnabled());
        redisProperties.setEnable(false);
        assertFalse(redisCache.isEnabled());
    }

    /* ==================== 对象缓存 ==================== */

    @Test
    @DisplayName("getObject：禁用时空操作；未命中/空串返回 null；命中返回反序列化对象")
    void getObject_behaviour() throws Exception {
        redisProperties.setEnable(false);
        assertNull(redisCache.getObject("cache:stats:g0:x", new TypeReference<List<String>>() {
        }));
        verifyNoInteractions(valueOperations);

        redisProperties.setEnable(true);
        when(valueOperations.get("cache:stats:g0:blank")).thenReturn("  ");
        assertNull(redisCache.getObject("cache:stats:g0:blank", new TypeReference<List<String>>() {
        }));

        when(valueOperations.get("cache:stats:g0:hit")).thenReturn("[\"a\"]");
        when(objectMapper.readValue(eq("[\"a\"]"), any(TypeReference.class))).thenReturn(List.of("a"));
        List<String> hit = redisCache.getObject("cache:stats:g0:hit", new TypeReference<List<String>>() {
        });
        assertNotNull(hit);
        assertEquals(List.of("a"), hit);
    }

    @Test
    @DisplayName("getObject：反序列化异常按未命中处理并删除脏 Key")
    void getObject_deserializationFailure_deletesDirtyKey() throws Exception {
        when(valueOperations.get("cache:bad")).thenReturn("{not-json");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenThrow(new RuntimeException("bad json"));

        assertNull(redisCache.getObject("cache:bad", new TypeReference<List<String>>() {
        }));
        verify(redisTemplate).delete("cache:bad");
    }

    @Test
    @DisplayName("setObject：禁用或值为 null 时空操作；正常写入 JSON + TTL；异常空转")
    void setObject_behaviour() throws Exception {
        redisProperties.setEnable(false);
        redisCache.setObject("k", List.of("a"), 60L);
        verifyNoInteractions(valueOperations);

        redisProperties.setEnable(true);
        redisCache.setObject("k", null, 60L);
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        when(objectMapper.writeValueAsString(any())).thenReturn("[\"a\"]");
        redisCache.setObject("k", List.of("a"), 60L);
        verify(valueOperations).set(eq("k"), eq("[\"a\"]"), eq(60L), eq(TimeUnit.SECONDS));

        doThrow(new RuntimeException("write failed"))
                .when(valueOperations).set(eq("k2"), anyString(), eq(60L), eq(TimeUnit.SECONDS));
        redisCache.setObject("k2", List.of("a"), 60L);   // 不抛异常
    }

    @Test
    @DisplayName("delete：禁用时空操作；异常空转")
    void delete_behaviour() {
        redisProperties.setEnable(false);
        redisCache.delete("k");
        verifyNoInteractions(redisTemplate);

        redisProperties.setEnable(true);
        doThrow(new RuntimeException("delete failed")).when(redisTemplate).delete("k2");
        redisCache.delete("k2");   // 不抛异常
    }

    /* ==================== 代次失效 ==================== */

    @Test
    @DisplayName("invalidateBusinessCaches：两族缓存代次各 +1")
    void invalidateBusinessCaches_bumpsBothGenerations() {
        redisCache.invalidateBusinessCaches();
        verify(valueOperations).increment(RedisCache.STATS_GEN_KEY);
        verify(valueOperations).increment(RedisCache.CLASSROOM_LIST_GEN_KEY);
    }

    @Test
    @DisplayName("currentGeneration：禁用/空值/非法/异常均按 0；正常解析带空格数值")
    void currentGeneration_behaviour() {
        redisProperties.setEnable(false);
        assertEquals(0L, redisCache.currentGeneration(RedisCache.STATS_GEN_KEY));

        redisProperties.setEnable(true);
        when(valueOperations.get(RedisCache.STATS_GEN_KEY)).thenReturn(" 7 ");
        assertEquals(7L, redisCache.currentGeneration(RedisCache.STATS_GEN_KEY));

        when(valueOperations.get(RedisCache.STATS_GEN_KEY)).thenReturn("");
        assertEquals(0L, redisCache.currentGeneration(RedisCache.STATS_GEN_KEY));

        when(valueOperations.get(RedisCache.STATS_GEN_KEY)).thenReturn("not-a-number");
        assertEquals(0L, redisCache.currentGeneration(RedisCache.STATS_GEN_KEY));

        when(valueOperations.get(RedisCache.STATS_GEN_KEY)).thenThrow(new RuntimeException("redis down"));
        assertEquals(0L, redisCache.currentGeneration(RedisCache.STATS_GEN_KEY));
    }

    @Test
    @DisplayName("bumpGeneration：禁用时空操作；异常空转不阻断业务")
    void bumpGeneration_behaviour() {
        redisProperties.setEnable(false);
        redisCache.bumpGeneration(RedisCache.STATS_GEN_KEY);
        verifyNoInteractions(valueOperations);

        redisProperties.setEnable(true);
        doThrow(new RuntimeException("incr failed")).when(valueOperations).increment(RedisCache.STATS_GEN_KEY);
        redisCache.bumpGeneration(RedisCache.STATS_GEN_KEY);   // 不抛异常
    }

    /* ==================== 互斥锁 ==================== */

    @Test
    @DisplayName("tryLock：禁用时直接成功；抢占成功/被占用/异常放行三种结果")
    void tryLock_behaviour() {
        redisProperties.setEnable(false);
        assertTrue(redisCache.tryLock("lock:key", 10L));

        redisProperties.setEnable(true);
        when(valueOperations.setIfAbsent(eq("lock:key"), eq("1"), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(true);
        assertTrue(redisCache.tryLock("lock:key", 10L));

        when(valueOperations.setIfAbsent(eq("lock:key"), eq("1"), eq(10L), eq(TimeUnit.SECONDS))).thenReturn(false);
        assertFalse(redisCache.tryLock("lock:key", 10L));

        when(valueOperations.setIfAbsent(eq("lock:key"), eq("1"), eq(10L), eq(TimeUnit.SECONDS)))
                .thenThrow(new RuntimeException("redis down"));
        assertTrue(redisCache.tryLock("lock:key", 10L));   // 降级放行，靠唯一索引兜底
    }

    @Test
    @DisplayName("unlock：委托 delete 删除锁 Key")
    void unlock_delegatesToDelete() {
        redisCache.unlock("lock:key");
        verify(redisTemplate).delete("lock:key");
    }

    /* ==================== 会话写入 / 鉴权状态写入 ==================== */

    @Test
    @DisplayName("saveToken：禁用或 userId 为空时空操作；写入异常空转")
    void saveToken_behaviour() {
        redisProperties.setEnable(false);
        redisCache.saveToken(1L, "token", 60L);
        verifyNoInteractions(valueOperations);

        redisProperties.setEnable(true);
        redisCache.saveToken(null, "token", 60L);
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        doThrow(new RuntimeException("write failed"))
                .when(valueOperations).set(eq("auth:token:1"), eq("token"), eq(60L), eq(TimeUnit.SECONDS));
        redisCache.saveToken(1L, "token", 60L);   // 不抛异常（后续校验 fail closed）
    }

    @Test
    @DisplayName("saveUserState：禁用 / 入参为空时空操作；正常写入 status|version")
    void saveUserState_behaviour() {
        redisProperties.setEnable(false);
        redisCache.saveUserState(1L, new RedisCache.UserState(1, 2));
        verifyNoInteractions(valueOperations);

        redisProperties.setEnable(true);
        redisCache.saveUserState(null, new RedisCache.UserState(1, 2));
        redisCache.saveUserState(1L, null);
        verifyNoInteractions(valueOperations);

        redisCache.saveUserState(1L, new RedisCache.UserState(1, 2));
        verify(valueOperations).set(eq("cache:user:state:1"), eq("1|2"), eq(60L), eq(TimeUnit.SECONDS));

        doThrow(new RuntimeException("write failed"))
                .when(valueOperations).set(eq("cache:user:state:1"), eq("1|2"), eq(60L), eq(TimeUnit.SECONDS));
        redisCache.saveUserState(1L, new RedisCache.UserState(1, 2));   // 不抛异常
    }

    @Test
    @DisplayName("getUserState：userId 为空返回 null（不查 Redis）")
    void getUserState_nullUserId() {
        assertNull(redisCache.getUserState(null));
        verifyNoInteractions(valueOperations);
    }

    @Test
    @DisplayName("removeUserState：userId 为空空操作；正常删除状态 Key")
    void removeUserState_behaviour() {
        redisCache.removeUserState(null);
        verifyNoInteractions(redisTemplate);

        redisCache.removeUserState(3L);
        verify(redisTemplate).delete("cache:user:state:3");
    }

    @Test
    @DisplayName("登录失败计数：Incr 异常返回 0（调用方退化内存计数）")
    void incrLoginFail_redisException_returnsZero() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(RedisCache.LOGIN_FAIL_KEY_PREFIX + "admin")).thenThrow(new RuntimeException("down"));
        assertEquals(0L, redisCache.incrLoginFail("admin", 600L));
    }

    @Test
    @DisplayName("登录失败计数：正常 INCR 后刷新 TTL")
    void incrLoginFail_setsExpire() {
        when(valueOperations.increment(RedisCache.LOGIN_FAIL_KEY_PREFIX + "admin")).thenReturn(2L);
        assertEquals(2L, redisCache.incrLoginFail("admin", 600L));
        verify(redisTemplate, times(1)).expire(RedisCache.LOGIN_FAIL_KEY_PREFIX + "admin", 600L, TimeUnit.SECONDS);
    }
}
