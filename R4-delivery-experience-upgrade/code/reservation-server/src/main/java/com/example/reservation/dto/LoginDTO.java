package com.example.reservation.dto;

import lombok.Data;

/**
 * 登录请求参数
 *
 * @author reservation-team
 */
@Data
public class LoginDTO {

    /** 登录账号 */
    private String username;

    /** 密码（明文，后端 BCrypt 校验） */
    private String password;

    /** 登录角色：0-学生，1-管理员（与账号角色必须匹配） */
    private Integer role;
}
