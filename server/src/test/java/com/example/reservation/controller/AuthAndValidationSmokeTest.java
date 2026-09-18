package com.example.reservation.controller;

import com.example.reservation.common.Constants;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权 + 参数校验冒烟测试（代码质量重构轮，MockMvc 半集成冒烟）
 * 走完整 Spring 上下文 + AuthInterceptor + 全局异常处理器，依赖 Docker MySQL 种子数据（database/init_db.sql）
 *
 * 覆盖：
 *  - 未登录访问受保护接口 → HTTP 401 + code=401（AuthInterceptor 直接写响应）
 *  - 学生 Token 访问管理员专属接口 → HTTP 403 + code=403
 *  - 管理员分页参数非法 → 业务校验 400（项目约定：业务错误 HTTP 200 + 响应体 code 表达语义）
 *
 * @author reservation-team
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthAndValidationSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("鉴权冒烟：未登录访问 /api/user/info 返回 401")
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/user/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("鉴权冒烟：无效 Token（Bearer xxx）访问受保护接口返回 401")
    void invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/user/info")
                        .header(Constants.TOKEN_HEADER, Constants.TOKEN_PREFIX + "invalid-token-xxx"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("正常路径冒烟：学生 Token 访问 /api/classroom/list 返回 200 且 records 存在")
    void studentList_returns200WithRecords() throws Exception {
        String token = login("zhangsan", "123456", Constants.ROLE_STUDENT);
        mockMvc.perform(get("/api/classroom/list")
                        .param("page", "1")
                        .param("size", "1")
                        .header(Constants.TOKEN_HEADER, Constants.TOKEN_PREFIX + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").exists());
    }

    @Test
    @DisplayName("鉴权冒烟：学生 Token 访问 /api/classroom/manage 返回 403")
    void studentTokenOnAdminApi_returns403() throws Exception {
        String token = login("zhangsan", "123456", Constants.ROLE_STUDENT);
        mockMvc.perform(get("/api/classroom/manage")
                        .header(Constants.TOKEN_HEADER, Constants.TOKEN_PREFIX + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("校验冒烟：管理员登录后 page=0 被分页校验拒绝")
    void invalidPage_rejected() throws Exception {
        String token = login("admin", "admin123", Constants.ROLE_ADMIN);
        mockMvc.perform(get("/api/classroom/manage")
                        .param("page", "0")
                        .param("size", "10")
                        .header(Constants.TOKEN_HEADER, Constants.TOKEN_PREFIX + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("页码必须大于等于 1"));
    }

    @Test
    @DisplayName("校验冒烟：管理员登录后 size=501 被分页校验拒绝")
    void invalidSize_rejected() throws Exception {
        String token = login("admin", "admin123", Constants.ROLE_ADMIN);
        mockMvc.perform(get("/api/classroom/manage")
                        .param("page", "1")
                        .param("size", "501")
                        .header(Constants.TOKEN_HEADER, Constants.TOKEN_PREFIX + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("每页条数必须在 1-500 之间"));
    }

    /** 登录辅助：POST /api/user/login 取 token（种子账号，role 须与账号类型一致） */
    private String login(String username, String password, int role) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"role\":" + role + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }
}
