package com.example.reservation.config;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 会话（单点会话）、鉴权状态缓存与登录锁写入单元测试。
 *
 * <p>会话撤销的权威凭据是数据库 token_version（见 AuthInterceptor / UserServiceImpl）；
 * 本类只断言 Redis 侧职责：会话比对、fail-closed、状态快照读写、撤销时清理两个 Key。</p>
 *
 * @author hyr
 */
@ExtendWith(MockitoExtension.class)
class RedisCacheTest {

    private static final Long USER_ID = 7L;
    private static final String TOKEN = "jwt-token";
    private static final String TOKEN_KEY = "auth:token:" + USER_ID;
    private static final String STATE_KEY = "cache:user:state:" + USER_ID;

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

    @Test
    @DisplayName("会话：仅明确禁用 Redis 时返回 SKIP")
    void validateToken_disabledRedis_returnsSkip() {
        redisProperties.setEnable(false);
        assertEquals(RedisCache.SessionStatus.SKIP, redisCache.validateToken(USER_ID, TOKEN));
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("会话：正常命中且 Token 一致时 VALID")
    void validateToken_matchingSession_returnsValid() {
        when(valueOperations.get(TOKEN_KEY)).thenReturn(TOKEN);
        assertEquals(RedisCache.SessionStatus.VALID, redisCache.validateToken(USER_ID, TOKEN));
    }

    @Test
    @DisplayName("会话：不存在或 Token 不一致时 INVALID")
    void validateToken_missingOrMismatch_returnsInvalid() {
        when(valueOperations.get(TOKEN_KEY)).thenReturn(null, "another-token");
        assertEquals(RedisCache.SessionStatus.INVALID, redisCache.validateToken(USER_ID, TOKEN));
        assertEquals(RedisCache.SessionStatus.INVALID, redisCache.validateToken(USER_ID, TOKEN));
    }

    @Test
    @DisplayName("会话：Redis 启用但校验异常时 fail closed，不得返回 SKIP")
    void validateToken_redisException_returnsInvalid() {
        when(valueOperations.get(TOKEN_KEY)).thenThrow(new RuntimeException("redis down"));
        assertEquals(RedisCache.SessionStatus.INVALID, redisCache.validateToken(USER_ID, TOKEN));
    }

    @Test
    @DisplayName("撤销会话：删除会话 Key 与鉴权状态缓存两个 Key，异常不向上抛")
    void invalidateSession_deletesSessionAndState() {
        redisCache.invalidateSession(USER_ID);
        verify(redisTemplate).delete(TOKEN_KEY);
        verify(redisTemplate).delete(STATE_KEY);
    }

    @Test
    @DisplayName("撤销会话：Redis 明确禁用时空操作")
    void invalidateSession_disabledRedis_doesNothing() {
        redisProperties.setEnable(false);
        redisCache.invalidateSession(USER_ID);
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("鉴权状态：写入 status|tokenVersion，读取可还原快照")
    void userState_writeAndRead() {
        redisCache.saveUserState(USER_ID, new RedisCache.UserState(1, 3));
        verify(valueOperations).set(org.mockito.ArgumentMatchers.eq(STATE_KEY),
                org.mockito.ArgumentMatchers.eq("1|3"),
                org.mockito.ArgumentMatchers.eq(60L),
                org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.SECONDS));

        when(valueOperations.get(STATE_KEY)).thenReturn("1|3");
        RedisCache.UserState state = redisCache.getUserState(USER_ID);
        assertEquals(1, state.status());
        assertEquals(3, state.tokenVersion());
    }

    @Test
    @DisplayName("鉴权状态：值格式非法时按未命中处理并删除脏 Key（回源查库）")
    void userState_malformedValue_fallsBackAndDeletesKey() {
        when(valueOperations.get(STATE_KEY)).thenReturn("1");     // 旧格式（仅状态）
        assertNull(redisCache.getUserState(USER_ID));
        verify(redisTemplate).delete(STATE_KEY);
    }

    @Test
    @DisplayName("鉴权状态：读写异常按未命中处理，不抛异常")
    void userState_redisException_returnsNull() {
        when(valueOperations.get(STATE_KEY)).thenThrow(new RuntimeException("redis down"));
        assertNull(redisCache.getUserState(USER_ID));
    }

    @Test
    @DisplayName("登录锁：共享写锁成功返回 true，异常返回 false 供业务层内存兜底")
    void lockLogin_reportsWriteResult() {
        assertTrue(redisCache.lockLogin("admin", 600L));
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(RedisCache.LOGIN_LOCK_KEY_PREFIX + "admin", "1", 600L,
                        java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(redisCache.lockLogin("admin", 600L));
    }
}
