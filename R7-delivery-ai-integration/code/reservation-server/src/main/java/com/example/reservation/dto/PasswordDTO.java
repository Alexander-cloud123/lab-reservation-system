package com.example.reservation.dto;

import lombok.Data;

/**
 * 修改密码请求体（R4 个人中心）
 * 必须验证原密码，新密码 BCrypt 加密存储
 *
 * @author reservation-team
 */
@Data
public class PasswordDTO {

    /** 原密码（BCrypt 校验） */
    private String oldPassword;

    /** 新密码（长度不少于 6 位） */
    private String newPassword;

    /** 确认新密码（须与新密码一致） */
    private String confirmPassword;
}
