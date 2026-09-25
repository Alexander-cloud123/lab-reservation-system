package com.example.reservation.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录成功返回：Token + 用户信息 + AI 开关
 *
 * @author reservation-team
 */
@Data
@AllArgsConstructor
public class LoginVO {

    /** 访问令牌（请求头 Authorization: Bearer {token}） */
    private String token;

    /** 用户信息（不含密码） */
    private UserVO user;

    /**
     * AI 有效开关（双开关口径，同 AiConfigService#isAiEnabled）。
     * 随登录一并下发，前端据此直接隐藏 AI 入口，省掉「探测一次 /ai/recommend 才知道要隐藏」的多余调用。
     */
    private Boolean aiEnabled;
}
