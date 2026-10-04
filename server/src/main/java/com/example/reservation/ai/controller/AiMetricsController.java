package com.example.reservation.ai.controller;

import com.example.reservation.ai.dto.AiMetricsVO;
import com.example.reservation.ai.support.AiMetrics;
import com.example.reservation.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 运行指标控制器（只读观测接口）
 *
 * 权限口径：路径映射到 /api/stats/ai/metrics，落在 Constants.ADMIN_API_PREFIXES 的 /api/stats 前缀内，
 * 由 AuthInterceptor 自动校验管理员角色（学生访问返回 403）——
 * 因此无需修改权限常量、也无需在本控制器内重复做角色判断。
 *
 * 只读不写：仅回传进程内计数器快照，不提供重置/写入能力。
 *
 * @author reservation-team
 */
@Tag(name = "AI 运行指标", description = "AI 模块进程内运行指标快照（只读，管理员专属）")
@RestController
@RequestMapping("/api/stats/ai")
public class AiMetricsController {

    @Resource
    private AiMetrics aiMetrics;

    /**
     * AI 运行指标快照：模型调用次数/耗时、降级次数按原因分桶、限流触发情况
     */
    @Operation(summary = "AI-运行指标快照", description = "返回模型调用次数与耗时、降级分桶统计、本地限流拒绝与上游 429 次数（进程内累加，重启清零）")
    @GetMapping("/metrics")
    public Result<AiMetricsVO> metrics() {
        return Result.success(aiMetrics.snapshot());
    }
}