package com.example.reservation.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.JwtUtil;
import com.example.reservation.common.ResultCode;
import com.example.reservation.common.UserContext;
import com.example.reservation.config.RedisCache;
import com.example.reservation.dto.LoginDTO;
import com.example.reservation.dto.PasswordDTO;
import com.example.reservation.dto.RegisterDTO;
import com.example.reservation.dto.UserInfoDTO;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.vo.LoginVO;
import com.example.reservation.vo.UserStatsVO;
import com.example.reservation.vo.UserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 用户业务分支覆盖补充（与 UserServiceImplSessionTest / UserServiceImplLoginLockTest 互补）
 *
 * <p>覆盖目标：登录参数与角色校验、注册全部校验分支、我的信息/统计、管理端用户分页与启停保护（含本人保护、幂等）、
 * 重置密码（含本人保护与撤销会话）、资料修改与改密校验、登出无上下文等分支。
 *
 * <p>BCrypt 只用一次真实哈希（静态字段），避免每个用例都付 ~100ms 的哈希成本。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplCoverageTest {

    /** 种子密码 123456 的真实 BCrypt 哈希（只算一次） */
    private static String passwordHash;

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private RedisCache redisCache;

    @Mock
    private AiConfigService aiConfigService;

    @InjectMocks
    private UserServiceImpl userService;

    @BeforeAll
    static void init() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), SysUser.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Reservation.class);
        passwordHash = BCrypt.hashpw("123456");
    }

    @BeforeEach
    void setUp() {
        UserContext.set(1L, "admin", Constants.ROLE_ADMIN);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /* ==================== 登录 ==================== */

    @Test
    @DisplayName("login：账号/密码/角色为空与角色非法被拒绝")
    void login_parameterValidation() {
        assertMessage("账号、密码、角色不能为空",
                () -> userService.login(login("", "123456", Constants.ROLE_STUDENT)));
        assertMessage("角色参数不合法",
                () -> userService.login(login("zhangsan", "123456", 9)));
    }

    @Test
    @DisplayName("login：成功签发 Token、写入会话并下发 AI 开关")
    void login_success() {
        SysUser user = user(2L, "zhangsan", "张三");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(jwtUtil.generateToken(anyLong(), any(), any(), any())).thenReturn("jwt-token");
        when(aiConfigService.isAiEnabled()).thenReturn(true);

        LoginVO vo = userService.login(login("  Zhangsan  ", "123456", Constants.ROLE_STUDENT));

        assertEquals("jwt-token", vo.getToken());
        assertTrue(vo.getAiEnabled());
        assertEquals("zhangsan", vo.getUser().getUsername());
        verify(redisCache).saveToken(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("login：账号禁用返回 403；角色不匹配返回 400")
    void login_statusAndRoleChecks() {
        SysUser disabled = user(2L, "zhangsan", "张三");
        disabled.setStatus(Constants.USER_STATUS_DISABLED);
        when(userMapper.selectOne(any())).thenReturn(disabled);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login(login("zhangsan", "123456", Constants.ROLE_STUDENT)));
        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("禁用"));

        SysUser student = user(2L, "zhangsan", "张三");
        when(userMapper.selectOne(any())).thenReturn(student);
        assertMessage("角色选择与账号类型不匹配",
                () -> userService.login(login("zhangsan", "123456", Constants.ROLE_ADMIN)));
    }

    /* ==================== 注册 ==================== */

    @Test
    @DisplayName("register：必填与长度校验分支逐一拒绝，不访问数据库")
    void register_validationBranches() {
        assertMessage("请完整填写必填信息", () -> userService.register(register(null, "123456", "123456", "张三", "2024001")));

        RegisterDTO longUsername = register("x".repeat(33), "123456", "123456", "张三", "2024001");
        assertMessage("账号长度不能超过 32 个字符", () -> userService.register(longUsername));

        RegisterDTO longName = register("zhangsan", "123456", "123456", "x".repeat(21), "2024001");
        assertMessage("姓名长度不能超过 20 个字符", () -> userService.register(longName));

        RegisterDTO longStudentNo = register("zhangsan", "123456", "123456", "张三", "x".repeat(21));
        assertMessage("学号长度不能超过 20 个字符", () -> userService.register(longStudentNo));

        RegisterDTO longEmail = register("zhangsan", "123456", "123456", "张三", "2024001");
        longEmail.setEmail("x".repeat(51) + "@a.com");
        assertMessage("邮箱长度不能超过 50 个字符", () -> userService.register(longEmail));

        assertMessage("密码长度不能少于 6 位",
                () -> userService.register(register("zhangsan", "12345", "12345", "张三", "2024001")));

        assertMessage("两次输入的密码不一致",
                () -> userService.register(register("zhangsan", "123456", "654321", "张三", "2024001")));

        RegisterDTO badPhone = register("zhangsan", "123456", "123456", "张三", "2024001");
        badPhone.setPhone("1234567890");
        assertMessage("手机号格式不正确", () -> userService.register(badPhone));

        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("register：账号已存在被拒绝；否则以学生身份插入（密码 BCrypt 存储）")
    void register_duplicateAndSuccess() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        assertMessage("该账号已被注册", () -> userService.register(register("zhangsan", "123456", "123456", "张三", "2024001")));
        verify(userMapper, never()).insert(any(SysUser.class));

        when(userMapper.selectCount(any())).thenReturn(0L);
        userService.register(register("zhangsan", "123456", "123456", "张三", "2024001"));
        verify(userMapper).insert(any(SysUser.class));
    }

    /* ==================== 我的信息 / 统计 ==================== */

    @Test
    @DisplayName("getCurrentUser：未登录 / 用户不存在返回 401，正常返回脱敏 VO")
    void getCurrentUser_branches() {
        UserContext.clear();
        BusinessException noLogin = assertThrows(BusinessException.class, () -> userService.getCurrentUser());
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), noLogin.getCode());

        UserContext.set(2L, "zhangsan", Constants.ROLE_STUDENT);
        when(userMapper.selectById(2L)).thenReturn(null);
        assertMessage("用户不存在或已被删除", () -> userService.getCurrentUser());

        when(userMapper.selectById(2L)).thenReturn(user(2L, "zhangsan", "张三"));
        UserVO vo = userService.getCurrentUser();
        assertEquals("zhangsan", vo.getUsername());
        assertNotNull(vo.getId());
    }

    @Test
    @DisplayName("getStats：通过率按「已通过 /（已通过 + 已驳回）」四舍五入一位；无记录为 0.0")
    void getStats_computesApprovalRate() {
        UserContext.set(2L, "zhangsan", Constants.ROLE_STUDENT);
        when(reservationMapper.selectCount(any())).thenReturn(5L, 2L, 3L, 1L);
        Reservation last = new Reservation();
        last.setCreateTime(LocalDateTime.of(2026, 11, 5, 10, 0));
        when(reservationMapper.selectOne(any())).thenReturn(last);

        UserStatsVO vo = userService.getStats();

        assertEquals(5L, vo.getTotalReservations());
        assertEquals(2L, vo.getMonthReservations());
        assertEquals(75.0, vo.getApprovalRate(), 0.001);
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 0), vo.getLastReservationTime());
    }

    @Test
    @DisplayName("getStats：全部未审核 → 通过率 0.0，最近预约为空")
    void getStats_noAuditRecords() {
        UserContext.set(2L, "zhangsan", Constants.ROLE_STUDENT);
        when(reservationMapper.selectCount(any())).thenReturn(0L, 0L, 0L, 0L);
        when(reservationMapper.selectOne(any())).thenReturn(null);

        UserStatsVO vo = userService.getStats();

        assertEquals(0.0, vo.getApprovalRate(), 0.001);
        assertNull(vo.getLastReservationTime());
    }

    /* ==================== 管理端用户 ==================== */

    @Test
    @DisplayName("pageUsers：分页参数校验 + 带筛选条件查询（结果不含密码字段）")
    void pageUsers_branches() {
        assertMessage("页码必须大于等于 1", () -> userService.pageUsers(0, 10, null, null, null));
        assertMessage("每页条数必须在 1-500 之间", () -> userService.pageUsers(1, 501, null, null, null));

        Page<SysUser> page = new Page<>();
        page.setRecords(java.util.List.of(user(2L, "zhangsan", "张三")));
        page.setTotal(1);
        when(userMapper.selectPage(any(), any())).thenReturn(page);

        var result = userService.pageUsers(1, 10, "张", Constants.ROLE_STUDENT, Constants.USER_STATUS_NORMAL);
        assertEquals(1, result.getRecords().size());
        assertEquals("zhangsan", result.getRecords().get(0).getUsername());
        // UserVO 不含 password 字段（列表接口天然脱敏，编译期即保证）
        assertNotNull(result.getRecords().get(0).getId());
    }

    @Test
    @DisplayName("updateUserStatus：ID/状态非法、用户不存在、操作自己均被拒绝")
    void updateUserStatus_rejectsInvalidCases() {
        assertMessage("用户 ID 不能为空", () -> userService.updateUserStatus(null, 0));
        assertMessage("状态参数不合法", () -> userService.updateUserStatus(2L, 9));

        when(userMapper.selectById(2L)).thenReturn(null);
        assertMessage("用户不存在", () -> userService.updateUserStatus(2L, 0));

        // 目标就是当前登录管理员自己
        when(userMapper.selectById(1L)).thenReturn(user(1L, "admin", "系统管理员"));
        assertMessage("不允许操作当前登录的管理员账号", () -> userService.updateUserStatus(1L, 0));
    }

    @Test
    @DisplayName("updateUserStatus：状态一致幂等返回；状态变化则更新并撤销该用户会话")
    void updateUserStatus_updatesAndRevokes() {
        SysUser target = user(2L, "zhangsan", "张三");
        target.setStatus(Constants.USER_STATUS_NORMAL);
        when(userMapper.selectById(2L)).thenReturn(target);

        userService.updateUserStatus(2L, Constants.USER_STATUS_NORMAL);
        verify(userMapper, never()).updateById(any(SysUser.class));

        when(userMapper.bumpTokenVersion(2L)).thenReturn(1);
        userService.updateUserStatus(2L, Constants.USER_STATUS_DISABLED);
        verify(userMapper).updateById(any(SysUser.class));
        verify(redisCache).invalidateSession(2L);
    }

    /* ==================== 重置密码 ==================== */

    @Test
    @DisplayName("resetPassword：ID 为空 / 用户不存在 / 操作自己 被拒绝")
    void resetPassword_rejectsInvalidCases() {
        assertMessage("用户 ID 不能为空", () -> userService.resetPassword(null));
        when(userMapper.selectById(2L)).thenReturn(null);
        assertMessage("用户不存在", () -> userService.resetPassword(2L));

        when(userMapper.selectById(1L)).thenReturn(user(1L, "admin", "系统管理员"));
        assertMessage("不允许操作当前登录的管理员账号", () -> userService.resetPassword(1L));
    }

    @Test
    @DisplayName("resetPassword：重置为默认密码并撤销会话")
    void resetPassword_updatesAndRevokes() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "zhangsan", "张三"));
        when(userMapper.bumpTokenVersion(2L)).thenReturn(1);

        userService.resetPassword(2L);

        verify(userMapper).updateById(any(SysUser.class));
        verify(redisCache).invalidateSession(2L);
    }

    /* ==================== 资料与改密 ==================== */

    @Test
    @DisplayName("updateInfo：姓名/手机号/学号/邮箱校验分支")
    void updateInfo_validationBranches() {
        assertMessage("姓名不能为空", () -> userService.updateInfo(info("", null, null, null)));
        assertMessage("姓名长度不能超过 20 个字符", () -> userService.updateInfo(info("x".repeat(21), null, null, null)));

        UserInfoDTO badPhone = info("张三", "1234567890", null, null);
        assertMessage("手机号格式不正确", () -> userService.updateInfo(badPhone));

        UserInfoDTO longStudentNo = info("张三", null, "x".repeat(21), null);
        assertMessage("学号长度不能超过 20 个字符", () -> userService.updateInfo(longStudentNo));

        UserInfoDTO longEmail = info("张三", null, null, "x".repeat(51) + "@a.com");
        assertMessage("邮箱长度不能超过 50 个字符", () -> userService.updateInfo(longEmail));
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("updateInfo：校验通过仅更新姓名/学号/手机/邮箱")
    void updateInfo_updatesProfile() {
        userService.updateInfo(info("张三", "13800000002", "2024001", "z@a.com"));

        verify(userMapper).updateById(any(SysUser.class));
    }

    @Test
    @DisplayName("updatePassword：空值 / 长度 / 不一致 / 用户不存在 / 原密码错误 被拒绝")
    void updatePassword_validationBranches() {
        assertMessage("原密码、新密码、确认密码不能为空", () -> userService.updatePassword(password(null, "123456", "123456")));
        assertMessage("新密码长度不能少于 6 位", () -> userService.updatePassword(password("123456", "12345", "12345")));
        assertMessage("两次输入的新密码不一致", () -> userService.updatePassword(password("123456", "abcdef", "abcdef1")));

        when(userMapper.selectById(1L)).thenReturn(null);
        BusinessException missing = assertThrows(BusinessException.class,
                () -> userService.updatePassword(password("123456", "abcdef", "abcdef")));
        assertEquals(ResultCode.UNAUTHORIZED.getCode(), missing.getCode());

        when(userMapper.selectById(1L)).thenReturn(user(1L, "admin", "系统管理员"));
        assertMessage("原密码错误", () -> userService.updatePassword(password("wrong-pass", "abcdef", "abcdef")));
    }

    @Test
    @DisplayName("updatePassword：改密成功后撤销本人会话")
    void updatePassword_successRevokesSession() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, "admin", "系统管理员"));
        when(userMapper.bumpTokenVersion(1L)).thenReturn(1);

        userService.updatePassword(password("123456", "abcdef", "abcdef"));

        verify(userMapper).updateById(any(SysUser.class));
        verify(redisCache).invalidateSession(1L);
    }

    /* ==================== 登出 ==================== */

    @Test
    @DisplayName("logout：无登录上下文时不做任何撤销；bump 影响 0 行时仅跳过（用户已被删除）")
    void logout_withoutContextOrMissingUser() {
        UserContext.clear();
        userService.logout();
        verifyNoInteractions(userMapper);

        UserContext.set(2L, "zhangsan", Constants.ROLE_STUDENT);
        when(userMapper.bumpTokenVersion(2L)).thenReturn(0);
        userService.logout();
        verify(redisCache, never()).invalidateSession(anyLong());
    }

    /* ==================== 工具方法 ==================== */

    private static SysUser user(Long id, String username, String name) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        u.setPassword(passwordHash);
        u.setName(name);
        u.setStudentNo("2024001");
        u.setRole(Constants.ROLE_STUDENT);
        u.setStatus(Constants.USER_STATUS_NORMAL);
        u.setTokenVersion(0);
        return u;
    }

    private static LoginDTO login(String username, String password, int role) {
        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setRole(role);
        return dto;
    }

    private static RegisterDTO register(String username, String password, String confirm, String name, String studentNo) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        dto.setConfirmPassword(confirm);
        dto.setName(name);
        dto.setStudentNo(studentNo);
        return dto;
    }

    private static UserInfoDTO info(String name, String phone, String studentNo, String email) {
        UserInfoDTO dto = new UserInfoDTO();
        dto.setName(name);
        dto.setPhone(phone);
        dto.setStudentNo(studentNo);
        dto.setEmail(email);
        return dto;
    }

    private static PasswordDTO password(String old, String newPassword, String confirm) {
        PasswordDTO dto = new PasswordDTO();
        dto.setOldPassword(old);
        dto.setNewPassword(newPassword);
        dto.setConfirmPassword(confirm);
        return dto;
    }

    private static void assertMessage(String expectedPart, Runnable action) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        assertTrue(ex.getMessage().contains(expectedPart), "实际消息：" + ex.getMessage());
    }
}
