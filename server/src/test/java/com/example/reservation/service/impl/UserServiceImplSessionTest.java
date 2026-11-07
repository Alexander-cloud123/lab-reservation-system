package com.example.reservation.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.example.reservation.common.Constants;
import com.example.reservation.common.JwtUtil;
import com.example.reservation.common.UserContext;
import com.example.reservation.config.RedisCache;
import com.example.reservation.dto.PasswordDTO;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户会话撤销语义测试（token_version 版本号撤销）。
 *
 * <p>撤销的权威凭据是 sys_user.token_version 原子自增：Redis 关闭、进程重启、多实例部署下
 * 旧 Token 都会因版本号不匹配被拦截器拒绝。因此这里断言的是「写库自增 + 提交后清理会话缓存」，
 * 而不再断言「Redis 删除失败 → 向调用方报告撤销未完全确认」（该旧语义已被版本号撤销取代）。</p>
 *
 * @author hyr
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplSessionTest {

    private static final Long USER_ID = 100L;
    private static final long TOKEN_TTL_SECONDS = 24 * 60 * 60L;
    private static final String OLD_PASSWORD = "old-pass-123";
    private static final String NEW_PASSWORD = "new-pass-123";

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private RedisCache redisCache;

    @InjectMocks
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        lenient().when(jwtUtil.getExpireSeconds()).thenReturn(TOKEN_TTL_SECONDS);
        lenient().when(redisCache.isEnabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("登出：自增 Token 版本并清理会话与状态缓存，始终正常完成")
    void logout_bumpsTokenVersionAndInvalidatesSession() {
        UserContext.set(USER_ID, "mixed-user", Constants.ROLE_STUDENT);
        when(userMapper.bumpTokenVersion(USER_ID)).thenReturn(1);

        assertDoesNotThrow(userService::logout);

        verify(userMapper).bumpTokenVersion(USER_ID);
        verify(redisCache).invalidateSession(USER_ID);
    }

    @Test
    @DisplayName("登出：Redis 未启用时仍自增版本（撤销不依赖 Redis）")
    void logout_redisDisabled_stillBumpsTokenVersion() {
        UserContext.set(USER_ID, "mixed-user", Constants.ROLE_STUDENT);
        when(userMapper.bumpTokenVersion(USER_ID)).thenReturn(1);

        assertDoesNotThrow(userService::logout);

        verify(userMapper).bumpTokenVersion(USER_ID);
    }

    @Test
    @DisplayName("登出：未登录上下文（userId 为空）不发数据库写操作")
    void logout_noUserContext_doesNothing() {
        userService.logout();
        verify(userMapper, never()).bumpTokenVersion(any());
    }

    @Test
    @DisplayName("改密：更新密码、自增版本、清理会话与失败计数")
    void updatePassword_revokesPreviousTokens() {
        stubCurrentUser();
        when(userMapper.bumpTokenVersion(USER_ID)).thenReturn(1);

        assertDoesNotThrow(() -> userService.updatePassword(passwordDto()));

        ArgumentCaptor<SysUser> updateCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(updateCaptor.capture());
        assertEquals(USER_ID, updateCaptor.getValue().getId());
        assertTrue(BCrypt.checkpw(NEW_PASSWORD, updateCaptor.getValue().getPassword()));
        verify(userMapper).bumpTokenVersion(USER_ID);
        verify(redisCache).invalidateSession(USER_ID);
        verify(redisCache).clearLoginTrack("mixedcase");
    }

    @Test
    @DisplayName("重置密码：管理员重置后自增版本，旧 Token 立即失效")
    void resetPassword_revokesTargetUserTokens() {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setUsername("MixedCase");
        user.setRole(Constants.ROLE_STUDENT);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.bumpTokenVersion(USER_ID)).thenReturn(1);
        // 管理员重置其他用户，不触发“不允许重置当前登录自己”的既有保护。
        UserContext.set(999L, "admin", Constants.ROLE_ADMIN);

        assertDoesNotThrow(() -> userService.resetPassword(USER_ID));

        ArgumentCaptor<SysUser> updateCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).updateById(updateCaptor.capture());
        assertTrue(BCrypt.checkpw(Constants.DEFAULT_PASSWORD, updateCaptor.getValue().getPassword()));
        verify(userMapper).bumpTokenVersion(USER_ID);
        verify(redisCache).invalidateSession(USER_ID);
        verify(redisCache).clearLoginTrack("mixedcase");
    }

    @Test
    @DisplayName("禁用用户：状态变更后自增版本，已签发 Token 失效")
    void updateUserStatus_revokesTokensOnStatusChange() {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setStatus(Constants.USER_STATUS_NORMAL);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.bumpTokenVersion(USER_ID)).thenReturn(1);
        UserContext.set(999L, "admin", Constants.ROLE_ADMIN);

        assertDoesNotThrow(() -> userService.updateUserStatus(USER_ID, Constants.USER_STATUS_DISABLED));

        verify(userMapper).updateById(any(SysUser.class));
        verify(userMapper).bumpTokenVersion(USER_ID);
        verify(redisCache).invalidateSession(USER_ID);
    }

    /** 构造本人改密上下文及数据库用户。 */
    private SysUser stubCurrentUser() {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setUsername("MixedCase");
        user.setPassword(BCrypt.hashpw(OLD_PASSWORD));
        user.setRole(Constants.ROLE_STUDENT);
        user.setStatus(Constants.USER_STATUS_NORMAL);
        user.setTokenVersion(0);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        UserContext.set(USER_ID, user.getUsername(), user.getRole());
        return user;
    }

    private PasswordDTO passwordDto() {
        PasswordDTO dto = new PasswordDTO();
        dto.setOldPassword(OLD_PASSWORD);
        dto.setNewPassword(NEW_PASSWORD);
        dto.setConfirmPassword(NEW_PASSWORD);
        return dto;
    }
}
