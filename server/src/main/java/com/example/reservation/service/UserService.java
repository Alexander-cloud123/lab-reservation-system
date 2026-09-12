package com.example.reservation.service;

import com.example.reservation.common.PageResult;
import com.example.reservation.dto.LoginDTO;
import com.example.reservation.dto.PasswordDTO;
import com.example.reservation.dto.RegisterDTO;
import com.example.reservation.dto.UserInfoDTO;
import com.example.reservation.vo.LoginVO;
import com.example.reservation.vo.UserStatsVO;
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

    /**
     * 管理员：分页查询用户列表（关键词：账号/姓名/学号；可加角色、状态筛选）
     * 排序按 create_time DESC；返回记录不含 password（UserVO 脱敏）
     */
    PageResult<UserVO> pageUsers(long page, long size, String keyword, Integer role, Integer status);

    /**
     * 管理员：启用/禁用用户（禁用后该用户无法登录；禁止操作当前登录管理员自己）
     */
    void updateUserStatus(Long id, Integer status);

    /**
     * 管理员：重置用户密码为默认密码（BCrypt 存储；禁止操作当前登录管理员自己）
     */
    void resetPassword(Long id);

    /**
     * 个人预约数据统计（R4 个人中心）：累计预约次数 / 本月预约数 / 审核通过率 / 最近一次预约时间
     */
    UserStatsVO getStats();

    /**
     * 修改个人信息（R4）：仅姓名 / 学号 / 邮箱 / 手机号，禁止修改密码字段
     */
    void updateInfo(UserInfoDTO dto);

    /**
     * 修改密码（R4）：验证原密码，新密码 BCrypt 加密存储
     */
    void updatePassword(PasswordDTO dto);
}
