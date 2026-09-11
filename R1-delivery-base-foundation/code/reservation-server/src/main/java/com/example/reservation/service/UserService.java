package com.example.reservation.service;

import com.example.reservation.dto.LoginDTO;
import com.example.reservation.dto.RegisterDTO;
import com.example.reservation.vo.LoginVO;
import com.example.reservation.vo.UserVO;

/**
 * 用户业务接口
 *
 * @author reservation-team
 */
public interface UserService {

    /**
     * 双角色登录：校验账号/密码/状态/角色匹配，签发 Token
     */
    LoginVO login(LoginDTO dto);

    /**
     * 学生注册：账号唯一、两次密码一致、密码 BCrypt 加密存储
     */
    void register(RegisterDTO dto);

    /**
     * 获取当前登录用户信息
     */
    UserVO getCurrentUser();
}
