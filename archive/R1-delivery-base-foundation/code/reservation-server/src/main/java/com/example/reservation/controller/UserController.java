package com.example.reservation.controller;

import com.example.reservation.common.Result;
import com.example.reservation.dto.LoginDTO;
import com.example.reservation.dto.RegisterDTO;
import com.example.reservation.service.UserService;
import com.example.reservation.vo.LoginVO;
import com.example.reservation.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户模块控制器（R1：登录 / 注册 / 当前用户信息）
 *
 * @author reservation-team
 */
@Tag(name = "用户模块", description = "登录、注册、当前用户信息")
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
}
