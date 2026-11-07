package com.example.reservation.common;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.example.reservation.config.RedisCache;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AuthInterceptor JWT 安全与 Token 版本撤销回归测试
 * （纯 Mockito，不依赖 Spring 上下文、数据库或 Redis）。
 *
 * <p>覆盖三类拒绝：签名/算法/exp/必需 claim 畸形、tokenVersion 与库中版本不一致（登出/改密/重置/禁用后的旧 Token）、
 * 账号被禁用；以及 Redis 状态缓存命中（零查库）与未命中（回源查库并回填）两条放行路径。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuthInterceptorSecurityTest {

    private static final String SECRET = "auth-interceptor-test-secret-0123456789";
    private static final long USER_ID = 42L;
    private static final String USERNAME = "admin";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private RedisCache redisCache;

    @InjectMocks
    private AuthInterceptor authInterceptor;

    @BeforeEach
    void setUp() {
        UserContext.clear();
        ReflectionTestUtils.setField(authInterceptor, "secret", SECRET);
        ReflectionTestUtils.setField(authInterceptor, "objectMapper", objectMapper);
        // 正常账号直接命中鉴权状态缓存（status + tokenVersion），避免有效 Token 用例回源数据库。
        lenient().when(redisCache.getUserState(anyLong()))
                .thenReturn(new RedisCache.UserState(Constants.USER_STATUS_NORMAL, 0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("alg=none 空签名伪造管理员 Token，即使 Redis SKIP 也返回 401")
    void algNoneWithSkippedSession_returns401() throws Exception {
        String token = withExpiration(requiredClaims(JWT.create())).sign(JWTSignerUtil.none());
        // 漏洞场景中 Redis 降级不能成为绕过 JWT 验签的条件；预期验签前/验签时即拒绝，不会调用会话校验。
        lenient().when(redisCache.validateToken(anyLong(), anyString()))
                .thenReturn(RedisCache.SessionStatus.SKIP);

        assertUnauthorized(token, "/api/user/manage");
    }

    @Test
    @DisplayName("HS256 Token 缺少 exp 返回 401")
    void missingExpiration_returns401() throws Exception {
        String token = requiredClaims(JWT.create()).sign(hs256());

        assertUnauthorized(token, "/api/classroom/list");
    }

    @Test
    @DisplayName("使用同一密钥的 HS384 Token 仍因非 HS256 返回 401")
    void nonHs256Algorithm_returns401() throws Exception {
        // 使用同一密钥生成真实 HS384 签名，确保拒绝原因是算法白名单，而非碰巧签名无效。
        String token = withExpiration(requiredClaims(JWT.create()))
                .sign(JWTSignerUtil.hs384(SECRET.getBytes(StandardCharsets.UTF_8)));

        assertUnauthorized(token, "/api/classroom/list");
    }

    @Test
    @DisplayName("有效 HS256 Token 在 Redis SKIP 降级时正常通过并写入 UserContext")
    void validHs256Token_passes() throws Exception {
        String token = validHs256Token();
        when(redisCache.validateToken(USER_ID, token)).thenReturn(RedisCache.SessionStatus.SKIP);
        MockHttpServletRequest request = request(token, "/api/user/manage");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = authInterceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        UserContext.LoginUser loginUser = UserContext.get();
        assertNotNull(loginUser);
        assertEquals(USER_ID, loginUser.userId());
        assertEquals(USERNAME, loginUser.username());
        assertEquals(Constants.ROLE_ADMIN, loginUser.role());
        verify(redisCache).getUserState(USER_ID);
        verify(redisCache).validateToken(USER_ID, token);
        verifyNoInteractions(sysUserMapper);
    }

    @Test
    @DisplayName("Token 版本号与库中不一致（登出/改密/重置/禁用后的旧 Token）→ 401，且不再做会话校验")
    void tokenVersionMismatch_returns401() throws Exception {
        // 缓存快照版本已自增到 1，Token 仍携带版本 0：模拟撤销后的旧 Token
        when(redisCache.getUserState(USER_ID))
                .thenReturn(new RedisCache.UserState(Constants.USER_STATUS_NORMAL, 1));
        String token = validHs256Token();

        assertRejected(token, "/api/classroom/list", HttpServletResponse.SC_UNAUTHORIZED,
                ResultCode.UNAUTHORIZED.getCode(), "登录已过期，请重新登录");
        verify(redisCache).getUserState(USER_ID);
        verify(redisCache, never()).validateToken(anyLong(), anyString());
        verifyNoInteractions(sysUserMapper);
    }

    @Test
    @DisplayName("账号被禁用 → 403（优先于版本号与会话校验）")
    void disabledUser_returns403() throws Exception {
        when(redisCache.getUserState(USER_ID))
                .thenReturn(new RedisCache.UserState(Constants.USER_STATUS_DISABLED, 0));
        String token = validHs256Token();

        assertRejected(token, "/api/classroom/list", HttpServletResponse.SC_FORBIDDEN,
                ResultCode.FORBIDDEN.getCode(), "账号已被禁用，请联系管理员");
        verify(redisCache).getUserState(USER_ID);
        verify(redisCache, never()).validateToken(anyLong(), anyString());
        verifyNoInteractions(sysUserMapper);
    }

    @Test
    @DisplayName("状态缓存未命中：回源查库一次、回填缓存并放行（Redis 关闭时每请求都走该路径）")
    void stateCacheMiss_loadsFromDbAndPasses() throws Exception {
        when(redisCache.getUserState(USER_ID)).thenReturn(null);
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setStatus(Constants.USER_STATUS_NORMAL);
        user.setTokenVersion(0);
        when(sysUserMapper.selectById(USER_ID)).thenReturn(user);
        String token = validHs256Token();
        when(redisCache.validateToken(USER_ID, token)).thenReturn(RedisCache.SessionStatus.SKIP);

        boolean allowed = authInterceptor.preHandle(request(token, "/api/user/manage"),
                new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        verify(sysUserMapper).selectById(USER_ID);
        verify(redisCache).saveUserState(USER_ID, new RedisCache.UserState(Constants.USER_STATUS_NORMAL, 0));
        verify(redisCache).validateToken(USER_ID, token);
    }

    @Test
    @DisplayName("状态缓存未命中且用户已被删除 → 401")
    void stateCacheMiss_userDeleted_returns401() throws Exception {
        when(redisCache.getUserState(USER_ID)).thenReturn(null);
        when(sysUserMapper.selectById(USER_ID)).thenReturn(null);

        assertRejected(validHs256Token(), "/api/classroom/list", HttpServletResponse.SC_UNAUTHORIZED,
                ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
        verify(sysUserMapper).selectById(USER_ID);
        verify(redisCache, never()).validateToken(anyLong(), anyString());
    }

    @ParameterizedTest(name = "畸形必需 claim：{0}={1}")
    @MethodSource("malformedRequiredClaims")
    @DisplayName("缺失或畸形必需 claims（含 tokenVersion）统一返回 401，不抛出转换异常")
    void malformedRequiredClaims_returns401InsteadOf500(String claimName, Object claimValue) throws Exception {
        String token = withExpiration(requiredClaims(JWT.create()).setPayload(claimName, claimValue))
                .sign(hs256());

        assertUnauthorized(token, "/api/classroom/list");
    }

    static Stream<Arguments> malformedRequiredClaims() {
        return Stream.of(
                Arguments.of("userId", null),
                Arguments.of("userId", "not-a-long"),
                Arguments.of("username", null),
                Arguments.of("username", " "),
                Arguments.of("role", null),
                Arguments.of("role", "not-an-integer"),
                Arguments.of("role", "2"),
                Arguments.of("tokenVersion", null),
                Arguments.of("tokenVersion", "not-an-integer"),
                Arguments.of("tokenVersion", -1)
        );
    }

    @ParameterizedTest(name = "非法 exp：{0}")
    @MethodSource("invalidExpirations")
    @DisplayName("非数值、非整数或乘法溢出的 exp 返回 401")
    void invalidExpiration_returns401(Object exp) throws Exception {
        String token = requiredClaims(JWT.create()).setPayload("exp", exp).sign(hs256());

        assertUnauthorized(token, "/api/classroom/list");
    }

    static Stream<Arguments> invalidExpirations() {
        return Stream.of(
                Arguments.of("1893456000"),
                Arguments.of(1.5D),
                Arguments.of(Long.MAX_VALUE)
        );
    }

    @Test
    @DisplayName("已过期的 HS256 Token 返回 401")
    void expiredToken_returns401() throws Exception {
        String token = requiredClaims(JWT.create())
                .setPayload("exp", System.currentTimeMillis() / 1000L - 1L)
                .sign(hs256());

        assertUnauthorized(token, "/api/classroom/list");
    }

    private String validHs256Token() {
        return withExpiration(requiredClaims(JWT.create())).sign(hs256());
    }

    private JWT requiredClaims(JWT jwt) {
        return jwt.setPayload("userId", String.valueOf(USER_ID))
                .setPayload("username", USERNAME)
                .setPayload("role", String.valueOf(Constants.ROLE_ADMIN))
                .setPayload("tokenVersion", "0");
    }

    private JWT withExpiration(JWT jwt) {
        return jwt.setPayload("exp", System.currentTimeMillis() / 1000L + 3600L);
    }

    private cn.hutool.jwt.signers.JWTSigner hs256() {
        return JWTSignerUtil.hs256(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private MockHttpServletRequest request(String token, String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
        request.addHeader(Constants.TOKEN_HEADER, Constants.TOKEN_PREFIX + token);
        return request;
    }

    private void assertUnauthorized(String token, String requestUri) throws Exception {
        assertRejected(token, requestUri, HttpServletResponse.SC_UNAUTHORIZED,
                ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
        // 解析阶段即拒绝：不得触达状态缓存、会话校验或数据库
        verify(redisCache, never()).getUserState(anyLong());
        verify(redisCache, never()).validateToken(anyLong(), anyString());
        verifyNoInteractions(sysUserMapper);
    }

    /** 断言拦截器拒绝并返回指定 HTTP 状态 + Result code + message（不校验内部交互）。 */
    private void assertRejected(String token, String requestUri, int expectedStatus,
                                int expectedCode, String expectedMessage) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = authInterceptor.preHandle(request(token, requestUri), response, new Object());

        assertFalse(allowed);
        assertEquals(expectedStatus, response.getStatus());
        var body = objectMapper.readTree(response.getContentAsString());
        assertEquals(expectedCode, body.path("code").asInt());
        assertEquals(expectedMessage, body.path("message").asText());
        assertNull(UserContext.get());
    }
}
