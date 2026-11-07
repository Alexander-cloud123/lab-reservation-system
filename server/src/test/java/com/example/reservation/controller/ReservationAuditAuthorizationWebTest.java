package com.example.reservation.controller;

import com.example.reservation.common.Constants;
import com.example.reservation.common.GlobalExceptionHandler;
import com.example.reservation.common.UserContext;
import com.example.reservation.service.impl.ReservationServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 审核路径变体的端到端 MVC 授权回归，不依赖数据库。 */
class ReservationAuditAuthorizationWebTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ReservationController controller = new ReservationController();
        // 使用真实 Service：学生若未被服务层拒绝，后续空 Mapper 会让测试立即失败。
        ReflectionTestUtils.setField(controller, "reservationService", new ReservationServiceImpl());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        UserContext.set(2L, "student", Constants.ROLE_STUDENT);
                        return true;
                    }

                    @Override
                    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                                Object handler, Exception ex) {
                        UserContext.clear();
                    }
                })
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/reservation/1/audit", "/api/reservation/+1/audit"})
    @DisplayName("普通数字与 +id 审核路径都不能被学生越权调用")
    void audit_rejectsStudentForNumericPathVariants(String path) throws Exception {
        mockMvc.perform(put(path)
                        .contentType("application/json")
                        .content("{\"status\":1}"))
                // 项目业务异常通过 HTTP 200 + Result.code 表达，关键是不能执行审核或返回 500。
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }
}
