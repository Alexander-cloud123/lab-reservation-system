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
import com.example.reservation.ai.support.ChatStreamSink;
import com.example.reservation.ai.support.SseChatStreamSink;
import com.example.reservation.common.Result;
import com.example.reservation.common.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 能力模块控制器（R7，需求文档 2.5 AI 能力模块 4 接口）
 * 全部 POST、只读不写（AI 仅查询数据/解析需求/给出建议，不执行任何业务操作）；
 * 权限口径（负责人已确认）：登录即可（学生端助手/推荐/解析 + 管理端合规校验均需访问），
 * 未登录 401；/api/ai 不在管理员前缀内（R7 已从 Constants.ADMIN_API_PREFIXES 移除）
 * ai.enable=false 时接口返回 enabled=false 的友好提示（不 500、不阻断核心），前端据此隐藏/禁用 AI 入口
 *
 * @author reservation-team
 */
@Slf4j
@Tag(name = "AI 能力模块", description = "智能教室推荐 / 自然语言解析 / 场景限定问答 / 合规校验（只读，登录即可）")
@RestController
@RequestMapping("/api/ai")
public class AiController {

    /** 流式 SSE 超时（ms）：略大于上游读超时 60s，避免容器先于上游断开 */
    private static final long STREAM_TIMEOUT_MS = 70_000L;

    @Resource
    private AiRecommendService aiRecommendService;

    @Resource
    private AiParseService aiParseService;

    @Resource
    private AiChatService aiChatService;

    @Resource
    private AiComplianceService aiComplianceService;

    @Resource(name = "aiStreamExecutor")
    private ThreadPoolTaskExecutor aiStreamExecutor;

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
    @PostMapping(value = "/chat", params = "stream!=true")
    public Result<AiChatVO> chat(@RequestBody AiChatRequest request) {
        return Result.success(aiChatService.chat(request.getQuestion()));
    }

    /**
     * 预约智能问答（SSE 流式，R12）：事件序列 meta → delta* → done（降级时 meta.degraded=true，FAQ 同样分段）
     * 入参校验在请求线程完成（SSE 建立前），校验失败与非流式一致返回 Result JSON；
     * userId 必须在请求线程捕获并传入异步任务（异步线程无 UserContext ThreadLocal）。
     */
    @Operation(summary = "AI-预约智能问答（流式SSE）", description = "text/event-stream：meta/delta/done 事件；?stream=true")
    @PostMapping(value = "/chat", params = "stream=true")
    public SseEmitter chatStream(@RequestBody AiChatRequest request) {
        // 1. SSE 建立前校验：BusinessException 由全局异常处理器转 Result JSON（与非流式完全一致）
        aiChatService.validateQuestion(request.getQuestion());
        // 2. 请求线程捕获登录身份
        Long userId = UserContext.getUserId();

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        ChatStreamSink sink = new SseChatStreamSink(emitter);
        final AtomicBoolean finished = new AtomicBoolean(false);
        emitter.onTimeout(() -> {
            finished.set(true);
            // 70s 超时收尾：尽力补发 error 帧，避免前端永久停留在打字机光标态；
            // 此时连接可能已被容器回收，发送失败由 safeSendError 吞掉，随后照常 complete
            safeSendError(sink, "AI 响应超时，请稍后再试");
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // 极端时序下二次收尾可能失败，忽略
            }
        });
        emitter.onError(t -> {
            finished.set(true);
            log.debug("SSE 连接异常/客户端断开：{}", t.toString());
        });

        try {
            aiStreamExecutor.execute(() -> {
                // 排队期间已超时/出错（70s 从请求建立即起算）：连接已死，跳过全部业务与上游调用，避免白耗配额
                if (finished.get()) {
                    return;
                }
                try {
                    aiChatService.streamChat(userId, request.getQuestion(), sink);
                } catch (Exception e) {
                    if (finished.get()) {
                        log.debug("流式问答在连接结束后异常：userId={}，{}", userId, e.toString());
                        return;
                    }
                    log.warn("流式问答执行异常：userId={}", userId, e);
                    safeSendError(sink, "服务暂时不可用，请稍后再试");
                } finally {
                    if (!finished.get()) {
                        try {
                            emitter.complete();
                        } catch (Exception ignore) {
                            // 二次收尾失败无需处理
                        }
                    }
                }
            });
        } catch (TaskRejectedException e) {
            // 线程池/队列打满：连接刚建立尚未发 meta，直接发 error 事件收尾
            log.warn("流式问答线程池已满，拒绝本次请求：userId={}", userId);
            safeSendError(sink, "AI 服务繁忙，请稍后再试");
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // 拒绝发生在请求线程，emitter 尚未派发；收尾失败无需处理
            }
        }
        return emitter;
    }

    /** error 事件发送兜底（忽略二次失败，如客户端已断开） */
    private void safeSendError(ChatStreamSink sink, String message) {
        try {
            sink.error(message);
        } catch (Exception ignored) {
            // 连接已关闭，无任何可做的补救
        }
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
