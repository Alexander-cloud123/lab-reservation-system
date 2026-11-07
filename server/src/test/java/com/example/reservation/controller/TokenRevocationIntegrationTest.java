package com.example.reservation.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.common.Constants;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Token 版本号持久撤销集成测试（真实 MySQL + MockMvc，显式关闭 Redis）。
 *
 * <p>关键点：`redis.enable=false` 时没有服务端会话可删、会话校验走 SKIP，
 * 旧 Token 若仍能被接受就说明撤销不持久。本类断言：
 * <ul>
 *   <li>登出后旧 Token 立即 401，重新登录正常；</li>
 *   <li>管理员重置密码后，目标用户旧 Token 立即 401，默认密码 123456 可重新登录；</li>
 *   <li>两种撤销都把 sys_user.token_version 真实自增（库中留痕，重启/多实例同样生效）。</li>
 * </ul>
 *
 * @author hyr
 */
@SpringBootTest(properties = {"redis.enable=false"})
@AutoConfigureMockMvc
class TokenRevocationIntegrationTest {

    private static final String STUDENT_NAME = "zhangsan";
    private static final String STUDENT_PASSWORD = "123456";
    private static final String ADMIN_NAME = "admin";
    private static final String ADMIN_PASSWORD = "admin123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Test
    @DisplayName("Redis 关闭：登出后旧 Token 立即 401，重新登录可用，版本号已自增")
    void logout_revokesTokenWithoutRedis() throws Exception {
        String token = login(STUDENT_NAME, STUDENT_PASSWORD, Constants.ROLE_STUDENT);
        Long userId = userId(STUDENT_NAME);
        int beforeVersion = tokenVersion(userId);

        mockMvc.perform(post("/api/user/logout")
                        .header(Constants.TOKEN_HEADER, Constants.TOKEN_PREFIX + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 旧 Token 携带的版本号已落后于库中当前值 → 401
        mockMvc.perform(get("/api/user/info")
                        .header(Constants.TOKEN_HEADER, Constants.TOKEN_PREFIX + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        int afterVersion = tokenVersion(userId);
        assertTrue(afterVersion > beforeVersion,
                "登出应把 token_version 自增（before=" + beforeVersion + ", after=" + afterVersion + "）");

        // 重新登录签发新版本号，立即可用
        String newToken = login(STUDENT_NAME, STUDENT_PASSWORD, Constants.ROLE_STUDENT);
        mockMvc.perform(get("/api/user/info")
                        .header(Constants.TOKEN_HEADER, Constants.TOKEN_PREFIX + newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("Redis 关闭：管理员重置密码后目标用户旧 Token 立即 401，默认密码可重新登录")
    void resetPassword_revokesTokenWithoutRedis() throws Exception {
        String adminToken = login(ADMIN_NAME, ADMIN_PASSWORD, Constants.ROLE_ADMIN);
        String studentToken = login(STUDENT_NAME, STUDENT_PASSWORD, Constants.ROLE_STUDENT);
        Long userId = userId(STUDENT_NAME);
        int beforeVersion = tokenVersion(userId);

        mockMvc.perform(put("/api/user/manage/{id}/password", userId)
                        .header(Constants.TOKEN_HEADER, Constants.TOKEN_PREFIX + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/user/info")
                        .header(Constants.TOKEN_HEADER, Constants.TOKEN_PREFIX + studentToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        int afterVersion = tokenVersion(userId);
        assertTrue(afterVersion > beforeVersion,
                "重置密码应把 token_version 自增（before=" + beforeVersion + ", after=" + afterVersion + "）");

        // 种子学生默认密码本就是 123456：重置后密码不变，但必须用新版本 Token 才能访问
        String newToken = login(STUDENT_NAME, Constants.DEFAULT_PASSWORD, Constants.ROLE_STUDENT);
        mockMvc.perform(get("/api/user/info")
                        .header(Constants.TOKEN_HEADER, Constants.TOKEN_PREFIX + newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /** 走真实登录接口获取 Token（含 tokenVersion claim）。 */
    private String login(String username, String password, int role) throws Exception {
        String body = objectMapper.writeValueAsString(new LoginBody(username, password, role));
        MvcResult result = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String token = node.path("data").path("token").asText();
        assertFalse(token.isBlank(), "登录应返回 Token");
        return token;
    }

    private Long userId(String username) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        assertNotNull(user, "种子用户应存在：" + username);
        return user.getId();
    }

    private int tokenVersion(Long userId) {
        SysUser user = userMapper.selectById(userId);
        return user == null || user.getTokenVersion() == null ? 0 : user.getTokenVersion();
    }

    /** 登录请求体（避免依赖 DTO 的可变状态）。 */
    private record LoginBody(String username, String password, int role) {
    }
}
