package com.example.reservation.dto;

import lombok.Data;

/**
 * 学生注册请求参数
 * 校验规则：账号唯一、两次密码一致、密码长度 ≥ 6、学生角色必填学号
 *
 * @author reservation-team
 */
@Data
public class RegisterDTO {

    /** 登录账号 */
    private String username;

    /** 密码 */
    private String password;

    /** 确认密码 */
    private String confirmPassword;

    /** 真实姓名 */
    private String name;

    /** 学号（学生角色必填） */
    private String studentNo;

    /** 联系电话 */
    private String phone;

    /** 邮箱 */
    private String email;
}
