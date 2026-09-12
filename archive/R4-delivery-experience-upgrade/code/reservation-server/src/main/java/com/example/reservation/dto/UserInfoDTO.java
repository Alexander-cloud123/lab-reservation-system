package com.example.reservation.dto;

import lombok.Data;

/**
 * 修改个人信息请求体（R4 个人中心）
 * 仅允许修改：姓名 / 学号 / 邮箱 / 手机号；禁止修改密码字段（密码走独立接口）
 *
 * @author reservation-team
 */
@Data
public class UserInfoDTO {

    /** 真实姓名（必填） */
    private String name;

    /** 学号（学生角色） */
    private String studentNo;

    /** 联系电话（选填，填写须为 11 位手机号） */
    private String phone;

    /** 邮箱（选填） */
    private String email;
}
