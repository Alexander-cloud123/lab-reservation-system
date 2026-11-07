package com.example.reservation.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.JwtUtil;
import com.example.reservation.config.RedisCache;
import com.example.reservation.dto.LoginDTO;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.vo.LoginVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录失败锁定（N1 修复）单元测试（纯 Mockito，不依赖数据库 / Redis）。
 * 断言策略：redisCache.isEnabled()=false 走内存降级路径；用反射推进锁定时间验证「到期解锁」；
 * 锁定行为口径不变：连续 5 次失败锁定 10 分钟，锁定期内正确口令也被拒（既有的 M3 行为不回归）。
 *
 * @author hyr
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplLoginLockTest {

    private static final String USERNAME = "lisi_lock_test";
    private static final String PASSWORD = "123456";
    private static final String WRONG_PASSWORD = "wrong-pass";
    private static final String FAIL_KEY = USERNAME;

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private RedisCache redisCache;

    /** 登录时向前端下发 AI 开关（R8）；本用例只关心登录锁定，开关固定按关闭处理 */
    @Mock
    private AiConfigService aiConfigService;

    @InjectMocks
    private UserServiceImpl userService;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), SysUser.class);
    }

    @BeforeEach
    void setUp() {
        // 内存降级路径：Redis 未启用（不依赖真实 Redis）。
        // lenient：持续 Redis 故障用例会覆盖为 true，避免 strict stubs 误报未使用
        lenient().when(redisCache.isEnabled()).thenReturn(false);
        // AI 开关（R8）：默认关闭；lenient：失败路径用例在构造 LoginVO 前即抛异常，不会用到该桩
        lenient().when(aiConfigService.isAiEnabled()).thenReturn(false);
        // 账号存在且口令正确（selectOne 永远返回该账号；口令由 BCrypt 真实比对）
        SysUser user = new SysUser();
        user.setId(100L);
        user.setUsername(USERNAME);
        user.setPassword(BCrypt.hashpw(PASSWORD));
        user.setRole(Constants.ROLE_STUDENT);
        user.setStatus(Constants.USER_STATUS_NORMAL);
        user.setTokenVersion(0);
        when(userMapper.selectOne(any())).thenReturn(user);
    }

    @Test
    @DisplayName("N1：第 5 次失败后进入锁定（锁定 Map 写入解锁时刻）")
    void fifthFailure_locksAccount() {
        // 前 4 次失败：口令错误 → 仍提示「账号或密码错误」
        for (int i = 1; i <= 4; i++) {
            BusinessException e = assertThrows(BusinessException.class, () -> login(WRONG_PASSWORD));
            assertTrue(e.getMessage().contains("账号或密码错误"), "第 " + i + " 次失败应提示密码错误：" + e.getMessage());
        }
        // 第 5 次失败：计数达到阈值，触发锁定（本次仍提示密码错误）
        assertThrows(BusinessException.class, () -> login(WRONG_PASSWORD));
        // 锁定 Map 已写入该账号（内存降级路径的锁定即解锁时刻记录）
        Map<String, Long> lockMap = lockMap();
        Long lockUntil = lockMap.get(FAIL_KEY);
        assertNotNull(lockUntil, "第 5 次失败后应写入锁定状态");
        assertTrue(lockUntil > System.currentTimeMillis(), "锁定时刻应位于未来");
    }

    @Test
    @DisplayName("N1：锁定期内第 6 次即使口令正确也被拒，并提示剩余时间")
    void sixthAttempt_correctPassword_stillRejected() {
        // 触发锁定：连续 5 次错误口令
        for (int i = 0; i < 5; i++) {
            assertThrows(BusinessException.class, () -> login(WRONG_PASSWORD));
        }
        // 第 6 次：口令正确但锁定未解除 → 仍被拒，提示临时锁定
        BusinessException e = assertThrows(BusinessException.class, () -> login(PASSWORD));
        assertTrue(e.getMessage().contains("临时锁定"), "锁定期间应提示临时锁定：" + e.getMessage());
    }

    @Test
    @DisplayName("N1：锁定解除后放行（到期后正确口令登录成功）")
    void lockExpired_allowsLogin() {
        // 触发锁定：连续 5 次错误口令
        for (int i = 0; i < 5; i++) {
            assertThrows(BusinessException.class, () -> login(WRONG_PASSWORD));
        }
        // 模拟锁定到期：把解锁时刻拨到过去（10 分钟窗口已过）
        lockMap().put(FAIL_KEY, System.currentTimeMillis() - 60_000L);
        // 成功路径会签发 Token（仅本用例用到，stub 放此处避免 strict stubs 报未使用）
        when(jwtUtil.generateToken(anyLong(), anyString(), any(), anyInt())).thenReturn("token-" + USERNAME);
        // 正确口令应登录成功
        LoginVO vo = login(PASSWORD);
        assertEquals("token-" + USERNAME, vo.getToken());
        assertNotNull(vo.getUser());
    }

    @Test
    @DisplayName("N1：内存降级路径顺序队列有长度上限（轮换用户名刷失败不无界增长）")
    void trackOrderQueue_isCapped() {
        // 超过 2×MAX + 100 个不同用户名各失败 1 次（Redis 未启用 → 走内存降级路径）
        int users = Constants.LOGIN_TRACK_MAX_ACCOUNTS * 2 + 100;
        for (int i = 0; i < users; i++) {
            final String username = "cap_user_" + i;
            assertThrows(BusinessException.class, () -> loginAs(username, WRONG_PASSWORD));
        }
        // 队列长度必须被 capTrackOrder 限制在 2×MAX 内（此前无上限、可无界增长）
        Queue<String> queue = trackOrder();
        assertTrue(queue.size() <= Constants.LOGIN_TRACK_MAX_ACCOUNTS * 2,
                "顺序队列应被限制在 2×MAX 内，实际 " + queue.size());
    }

    @Test
    @DisplayName("N1：Redis 持续读写故障时第 5 次失败形成真实内存锁，第 6 次正确口令仍被拒")
    void redisPersistentFailure_fifthFailureCreatesRealMemoryLock() {
        // Redis 开关始终开启，但计数、写锁、读锁及 TTL 读取持续不可用；绝不手工桩 isLoginLocked=true。
        when(redisCache.isEnabled()).thenReturn(true);
        when(redisCache.incrLoginFail(anyString(), anyLong())).thenReturn(0L);
        when(redisCache.lockLogin(anyString(), anyLong())).thenReturn(false);
        when(redisCache.isLoginLocked(anyString())).thenReturn(false);
        when(redisCache.getLoginLockRemainSeconds(anyString())).thenReturn(0L);

        for (int i = 0; i < 5; i++) {
            assertThrows(BusinessException.class, () -> login(WRONG_PASSWORD));
        }

        // 锁写入后计数被清理，真正的 10 分钟内存 TTL 锁已存在。
        assertFalse(failMap().containsKey(FAIL_KEY), "达到阈值并写锁后应清理内存失败计数");
        Long lockUntil = lockMap().get(FAIL_KEY);
        assertNotNull(lockUntil, "Redis 写锁失败后必须写内存锁");
        assertTrue(lockUntil > System.currentTimeMillis(), "内存锁必须具有未来解锁时刻");
        verify(redisCache).lockLogin(FAIL_KEY, Constants.LOGIN_LOCK_MINUTES * 60L);

        // 第 6 次仍由真实锁定状态拦截，而不是依赖测试手工伪造 Redis 读锁命中。
        BusinessException e = assertThrows(BusinessException.class, () -> login(PASSWORD));
        assertTrue(e.getMessage().contains("临时锁定"), "持续故障下也必须有真实锁定：" + e.getMessage());
        verify(userMapper, times(5)).selectOne(any());
    }

    @Test
    @DisplayName("N1：登录查询和锁桶统一 trim + 小写，锁桶不包含 role")
    void loginUsername_normalizedForQueryAndLockBucket() {
        assertThrows(BusinessException.class, () -> loginAs("  LISI_LOCK_TEST  ", WRONG_PASSWORD,
                Constants.ROLE_ADMIN));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<SysUser>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectOne(queryCaptor.capture());
        // MyBatis-Plus 参数值在生成 SQL 片段时才填充，不能读取调用前的懒参数 Map。
        assertTrue(queryCaptor.getValue().getCustomSqlSegment().contains("username"));
        assertTrue(queryCaptor.getValue().getParamNameValuePairs().containsValue(USERNAME),
                "登录查询参数必须与锁桶使用同一规范化账号，实际=" + queryCaptor.getValue().getParamNameValuePairs());
        assertTrue(failMap().containsKey(FAIL_KEY), "锁桶只能使用规范化账号");
        assertFalse(failMap().containsKey(USERNAME + ":" + Constants.ROLE_ADMIN),
                "锁桶不得包含客户端 role");
    }

    @Test
    @DisplayName("N1：大小写、尾随空格和 role 变体不能换桶绕过第 5 次锁定")
    void usernameAndRoleVariants_shareSingleLockBucket() {
        String[] names = {USERNAME, "LISI_LOCK_TEST", "Lisi_Lock_Test", USERNAME + " ", "  LISI_LOCK_TEST  "};
        int[] roles = {Constants.ROLE_STUDENT, Constants.ROLE_ADMIN, Constants.ROLE_STUDENT,
                Constants.ROLE_ADMIN, Constants.ROLE_STUDENT};
        for (int i = 0; i < 5; i++) {
            final String name = names[i];
            final int role = roles[i];
            assertThrows(BusinessException.class, () -> loginAs(name, WRONG_PASSWORD, role));
        }

        BusinessException e = assertThrows(BusinessException.class,
                () -> loginAs(" LISI_LOCK_TEST ", PASSWORD, Constants.ROLE_ADMIN));
        assertTrue(e.getMessage().contains("临时锁定"), "变体必须共用同一个锁桶：" + e.getMessage());
        // 第 6 次在查询数据库前即被锁定拦截，证明 5 次变体失败确实进入同一桶。
        verify(userMapper, times(5)).selectOne(any());
    }

    /** 构造登录 DTO 并调用（错误或正确口令由入参决定） */
    private LoginVO login(String password) {
        return loginAs(USERNAME, password);
    }

    /** 以指定用户名登录（轮换用户名验证队列上限）。 */
    private LoginVO loginAs(String username, String password) {
        return loginAs(username, password, Constants.ROLE_STUDENT);
    }

    /** 以指定用户名和客户端角色登录，验证查询规范化及锁桶与角色无关。 */
    private LoginVO loginAs(String username, String password, int role) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setRole(role);
        return userService.login(dto);
    }

    /** 反射读取内存锁定 Map（仅测试用：推进锁定时间验证到期解锁） */
    @SuppressWarnings("unchecked")
    private Map<String, Long> lockMap() {
        try {
            Field f = UserServiceImpl.class.getDeclaredField("LOGIN_LOCK_UNTIL");
            f.setAccessible(true);
            return (Map<String, Long>) f.get(userService);
        } catch (Exception e) {
            throw new IllegalStateException("反射读取 LOGIN_LOCK_UNTIL 失败", e);
        }
    }

    /** 反射读取内存写入顺序队列（仅测试用：验证长度上限） */
    @SuppressWarnings("unchecked")
    private Queue<String> trackOrder() {
        return (Queue<String>) ReflectionTestUtils.getField(userService, "LOGIN_TRACK_ORDER");
    }

    /** 反射读取内存失败计数 Map（仅测试用：验证 Redis 计数不可用时退化内存计数） */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> failMap() {
        return (Map<String, Integer>) ReflectionTestUtils.getField(userService, "LOGIN_FAIL_COUNT");
    }
}
