package com.example.reservation.ai.controller;

import com.example.reservation.ai.dto.AiChatRequest;
import com.example.reservation.ai.dto.AiChatVO;
import com.example.reservation.ai.dto.AiComplianceRequest;
import com.example.reservation.ai.dto.AiComplianceVO;
import com.example.reservation.ai.dto.AiParseRequest;
import com.example.reservation.ai.dto.AiParseVO;
import com.example.reservation.ai.dto.AiRecommendRequest;
import com.example.reservation.ai.dto.AiRecommendVO;
import com.example.reservation.ai.service.AiChatService;
import com.example.reservation.ai.service.AiComplianceService;
import com.example.reservation.ai.service.AiParseService;
import com.example.reservation.ai.service.AiRecommendService;
import com.example.reservation.common.Result;
import com.example.reservation.common.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 能力模块控制器（R7，需求文档 2.5 AI 能力模块 4 接口）
 * 全部 POST、只读不写（AI 仅查询数据/解析需求/给出建议，不执行任何业务操作）；
 * 权限口径（负责人已确认）：登录即可（学生端助手/推荐/解析 + 管理端合规校验均需访问），
 * 未登录 401；/api/ai 不在管理员前缀内（R7 已从 Constants.ADMIN_API_PREFIXES 移除）
 * ai.enable=false 时接口返回 enabled=false 的友好提示（不 500、不阻断核心），前端据此隐藏/禁用 AI 入口
 *
 * @author reservation-team
 */
@Tag(name = "AI 能力模块", description = "智能教室推荐 / 自然语言解析 / 场景限定问答 / 合规校验（只读，登录即可）")
@RestController
@RequestMapping("/api/ai")
public class AiController {

    @Resource
    private AiRecommendService aiRecommendService;

    @Resource
    private AiParseService aiParseService;

    @Resource
    private AiChatService aiChatService;

    @Resource
    private AiComplianceService aiComplianceService;

    /**
     * 智能教室推荐：Top3 教室 + 推荐理由（基于当前登录用户历史习惯 + 实时空闲）
     * 推荐对象固定取 UserContext 当前用户，不信任请求体传入的 userId，防止越权读取他人预约偏好（P1 修复）
     */
    @Operation(summary = "AI-智能教室推荐", description = "基于当前登录用户历史预约习惯（时段/楼栋/类型/人数）+实时空闲推荐Top3教室及理由")
    @PostMapping("/recommend")
    public Result<AiRecommendVO> recommend(@RequestBody(required = false) AiRecommendRequest request) {
        return Result.success(aiRecommendService.recommend(UserContext.getUserId()));
    }

    /**
     * 自然语言预约解析：口语化描述 → 结构化预约参数（日期/时段/人数/类型/用途）
     */
    @Operation(summary = "AI-自然语言预约解析", description = "口语化文本→结构化JSON（date/startTime/endTime/capacity/roomType/purpose），识别失败返回error")
    @PostMapping("/parse-reservation")
    public Result<AiParseVO> parseReservation(@RequestBody AiParseRequest request) {
        return Result.success(aiParseService.parse(request.getText()));
    }

    /**
     * 预约智能问答：仅解答预约/教室/个人记录相关问题，无关问题返回预设话术
     */
    @Operation(summary = "AI-预约智能问答", description = "场景绝对限定：仅回答预约/教室/个人记录相关问题；无关问题返回预设话术")
    @PostMapping("/chat")
    public Result<AiChatVO> chat(@RequestBody AiChatRequest request) {
        return Result.success(aiChatService.chat(request.getQuestion()));
    }

    /**
     * 预约合规校验：用途 → compliant/reason（辅助管理员审核，只提示不改状态）
     */
    @Operation(summary = "AI-预约合规校验", description = "输入预约用途→合规结果；只提示不改状态，审核动作仍由管理员手动执行")
    @PostMapping("/compliance-check")
    public Result<AiComplianceVO> complianceCheck(@RequestBody AiComplianceRequest request) {
        return Result.success(aiComplianceService.check(request.getPurpose()));
    }
}
