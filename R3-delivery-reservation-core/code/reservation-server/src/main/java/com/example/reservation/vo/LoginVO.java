package com.example.reservation.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录成功返回：Token + 用户信息
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
}
