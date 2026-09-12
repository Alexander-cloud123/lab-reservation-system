package com.example.reservation.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.JwtUtil;
import com.example.reservation.common.ResultCode;
import com.example.reservation.common.UserContext;
import com.example.reservation.dto.LoginDTO;
import com.example.reservation.dto.RegisterDTO;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.service.UserService;
import com.example.reservation.vo.LoginVO;
import com.example.reservation.vo.UserVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * 用户业务实现
 *
 * @author reservation-team
 */
@Service
public class UserServiceImpl implements UserService {

    @Resource
    private SysUserMapper userMapper;

    @Resource
    private JwtUtil jwtUtil;

    @Override
    public LoginVO login(LoginDTO dto) {
        // 参数校验
        if (StrUtil.hasBlank(dto.getUsername(), dto.getPassword()) || dto.getRole() == null) {
            throw new BusinessException("账号、密码、角色不能为空");
        }
        if (dto.getRole() != Constants.ROLE_STUDENT && dto.getRole() != Constants.ROLE_ADMIN) {
            throw new BusinessException("角色参数不合法");
        }

        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        // 账号不存在与密码错误统一提示，避免账号枚举
        if (user == null || !BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("账号或密码错误");
        }
        // 状态校验
        if (user.getStatus() == Constants.USER_STATUS_DISABLED) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "账号已被禁用，请联系管理员");
        }
        // 角色匹配校验（学生账号不能以管理员身份登录）
        if (user.getRole() != dto.getRole()) {
            throw new BusinessException("角色选择与账号类型不匹配");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginVO(token, UserVO.from(user));
    }

    @Override
    public void register(RegisterDTO dto) {
        // 必填项校验
        if (StrUtil.hasBlank(dto.getUsername(), dto.getPassword(), dto.getConfirmPassword(), dto.getName(), dto.getStudentNo())) {
            throw new BusinessException("请完整填写必填信息（账号/密码/确认密码/姓名/学号）");
        }
        if (dto.getUsername().length() > 32) {
            throw new BusinessException("账号长度不能超过 32 个字符");
        }
        if (dto.getPassword().length() < Constants.PASSWORD_MIN_LENGTH) {
            throw new BusinessException("密码长度不能少于 6 位");
        }
        // 两次密码一致性校验
        if (!dto.getPassword().equals(dto.getConfirmPassword())) {
            throw new BusinessException("两次输入的密码不一致");
        }
        // 手机号格式校验（选填，填写则必须为 11 位手机号）
        if (StrUtil.isNotBlank(dto.getPhone()) && !dto.getPhone().matches("1\\d{10}")) {
            throw new BusinessException("手机号格式不正确");
        }
        // 账号唯一性校验
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException("该账号已被注册，请更换账号");
        }

        // 注册角色固定为学生，默认正常状态，密码 BCrypt 加密存储（数据库中无明文）
        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(BCrypt.hashpw(dto.getPassword()));
        user.setName(dto.getName());
        user.setStudentNo(dto.getStudentNo());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setRole(Constants.ROLE_STUDENT);
        user.setStatus(Constants.USER_STATUS_NORMAL);
        userMapper.insert(user);
    }

    @Override
    public UserVO getCurrentUser() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户不存在或已被删除");
        }
        return UserVO.from(user);
    }
}
