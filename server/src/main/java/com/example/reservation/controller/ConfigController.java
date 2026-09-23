package com.example.reservation.controller;

import com.example.reservation.common.Constants;
import com.example.reservation.common.Result;
import com.example.reservation.common.TimeUtil;
import com.example.reservation.vo.BookingRulesVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统配置下发控制器（只读、轻量）
 *
 * <p>用途：把前端必须与后端保持一致的业务规则（可预约时段窗口、单次预约时长上限）由后端下发。
 * 前端不再手工镜像 {@link Constants}，避免后端调整规则后前端提示文案与实时校验口径漂移。
 *
 * <p>权限：登录即可访问（路径不在管理员专属前缀内，拦截器仅校验 Token 有效性）。
 *
 * @author reservation-team
 */
@Tag(name = "系统配置", description = "前端所需业务规则下发（只读）")
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    /**
     * 预约规则：可预约时段窗口 + 单次预约时长上限
     * 全部由 Constants 派生，保证前端实时校验与后端二次校验同一口径
     */
    @Operation(summary = "预约规则", description = "可预约时段窗口（HH:mm）与单次预约最长时长（小时），供前端实时校验与提示文案使用")
    @GetMapping("/booking-rules")
    public Result<BookingRulesVO> bookingRules() {
        return Result.success(new BookingRulesVO(
                TimeUtil.formatTime(Constants.DAILY_SLOT_START),
                TimeUtil.formatTime(Constants.DAILY_SLOT_END),
                Constants.MAX_RESERVATION_HOURS));
    }
}
