package com.example.reservation.controller;

import com.example.reservation.common.PageResult;
import com.example.reservation.common.Result;
import com.example.reservation.dto.LoginDTO;
import com.example.reservation.dto.RegisterDTO;
import com.example.reservation.dto.UserStatusDTO;
import com.example.reservation.service.UserService;
import com.example.reservation.vo.LoginVO;
import com.example.reservation.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户模块控制器（R1：登录 / 注册 / 当前用户信息；R2：管理端用户管理）
 *
 * @author reservation-team
 */
@Tag(name = "用户模块", description = "登录、注册、当前用户信息、管理端用户管理")
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 双角色登录（学生/管理员），成功签发 Token
     */
    @Operation(summary = "登录", description = "账号+密码+角色登录，返回 Token 与用户信息")
    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody LoginDTO dto) {
        return Result.success("登录成功", userService.login(dto));
    }

    /**
     * 学生注册（账号唯一 / 两次密码一致 / BCrypt 加密存储）
     */
    @Operation(summary = "注册", description = "学生自主注册，账号唯一、两次密码一致，密码 BCrypt 加密")
    @PostMapping("/register")
    public Result<Void> register(@RequestBody RegisterDTO dto) {
        userService.register(dto);
        return Result.<Void>success("注册成功，请登录", null);
    }

    /**
     * 当前登录用户信息（受保护接口，未登录返回 401）
     */
    @Operation(summary = "当前用户信息", description = "获取当前登录用户信息（需携带 Token）")
    @GetMapping("/info")
    public Result<UserVO> info() {
        return Result.success(userService.getCurrentUser());
    }

    /**
     * 管理端：用户分页列表 + 多条件查询（关键词：账号/姓名/学号；角色、状态筛选）
     * 管理员专属接口，拦截器按 /api/user/manage 前缀校验角色，学生 Token 访问返回 403
     */
    @Operation(summary = "用户管理-分页列表", description = "关键词（账号/姓名/学号）+角色+状态筛选，按 create_time DESC 排序，不含 password")
    @GetMapping("/manage")
    public Result<PageResult<UserVO>> pageUsers(@RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "10") long size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) Integer role,
                                                @RequestParam(required = false) Integer status) {
        return Result.success(userService.pageUsers(page, size, keyword, role, status));
    }

    /**
     * 管理端：启用/禁用用户（禁用后该用户无法登录；禁止操作当前登录管理员自己）
     */
    @Operation(summary = "用户管理-启用/禁用", description = "status：0-禁用，1-正常")
    @PutMapping("/manage/{id}/status")
    public Result<Void> updateUserStatus(@PathVariable Long id, @RequestBody UserStatusDTO dto) {
        userService.updateUserStatus(id, dto.getStatus());
        return Result.<Void>success("操作成功", null);
    }

    /**
     * 管理端：重置用户密码为默认密码（123456，BCrypt 存储；禁止操作当前登录管理员自己）
     */
    @Operation(summary = "用户管理-重置密码", description = "重置为默认密码 123456，BCrypt 加密存储")
    @PutMapping("/manage/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id) {
        userService.resetPassword(id);
        return Result.<Void>success("密码已重置为默认密码", null);
    }
}
