package com.example.reservation.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.JwtUtil;
import com.example.reservation.common.PageResult;
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

    @Override
    public PageResult<UserVO> pageUsers(long page, long size, String keyword, Integer role, Integer status) {
        // 分页参数合法性校验（防恶意传参）
        if (page < 1) {
            throw new BusinessException("页码必须大于等于 1");
        }
        if (size < 1 || size > 500) {
            throw new BusinessException("每页条数必须在 1-500 之间");
        }
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                // 关键词：账号 / 姓名 / 学号 模糊匹配
                .and(StrUtil.isNotBlank(keyword), w -> w
                        .like(SysUser::getUsername, keyword)
                        .or().like(SysUser::getName, keyword)
                        .or().like(SysUser::getStudentNo, keyword))
                // 角色 / 状态筛选
                .eq(role != null, SysUser::getRole, role)
                .eq(status != null, SysUser::getStatus, status)
                // 统一排序：创建时间倒序（spec.md 5.1）
                .orderByDesc(SysUser::getCreateTime);
        Page<SysUser> result = userMapper.selectPage(new Page<>(page, size), wrapper);
        // 实体 → VO 转换，剔除 password 字段，保证列表接口不返回密码
        return PageResult.of(result, UserVO::from);
    }

    @Override
    public void updateUserStatus(Long id, Integer status) {
        if (id == null) {
            throw new BusinessException("用户 ID 不能为空");
        }
        if (status == null || (status != Constants.USER_STATUS_DISABLED && status != Constants.USER_STATUS_NORMAL)) {
            throw new BusinessException("状态参数不合法（0-禁用，1-正常）");
        }
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        // 防护：管理员不允许禁用/启用当前登录的自己（R2 规则）
        if (id.equals(UserContext.getUserId())) {
            throw new BusinessException("不允许操作当前登录的管理员账号");
        }
        // 目标状态与当前状态一致时直接返回（幂等）
        if (user.getStatus() != null && user.getStatus().equals(status)) {
            return;
        }
        SysUser update = new SysUser();
        update.setId(id);
        update.setStatus(status);
        userMapper.updateById(update);
    }

    @Override
    public void resetPassword(Long id) {
        if (id == null) {
            throw new BusinessException("用户 ID 不能为空");
        }
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        // 防护：管理员不允许重置当前登录的自己（R2 规则）
        if (id.equals(UserContext.getUserId())) {
            throw new BusinessException("不允许操作当前登录的管理员账号");
        }
        // 重置为默认密码，BCrypt 加密存储（数据库中无明文）
        SysUser update = new SysUser();
        update.setId(id);
        update.setPassword(BCrypt.hashpw(Constants.DEFAULT_PASSWORD));
        userMapper.updateById(update);
    }
}
