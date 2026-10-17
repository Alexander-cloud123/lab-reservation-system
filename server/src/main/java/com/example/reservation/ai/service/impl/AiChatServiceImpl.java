package com.example.reservation.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.ai.config.AgnesClient;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.config.AiPrompts;
import com.example.reservation.ai.dto.AiChatHistoryMessage;
import com.example.reservation.ai.dto.AiChatVO;
import com.example.reservation.ai.service.AiChatService;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.support.AiChatFallback;
import com.example.reservation.ai.support.AiHash;
import com.example.reservation.ai.support.AiInvoker;
import com.example.reservation.ai.support.AiJsonSupport;
import com.example.reservation.ai.support.AiMetrics;
import com.example.reservation.ai.support.AiResultCache;
import com.example.reservation.ai.support.AiStreamText;
import com.example.reservation.ai.support.ChatStreamSink;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.TimeUtil;
import com.example.reservation.common.UserContext;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 预约智能问答实现（R7）
 * 场景绝对限定（需求文档 1.4 AI 业务规则 1）：仅解答预约/教室/个人记录相关问题，
 * 无关问题统一返回预设话术；个人数据通过检索增强注入上下文（最近预约 + 用户概况）
 *
 * @author reservation-team
 */
@Slf4j
@Service
public class AiChatServiceImpl implements AiChatService {

    @Resource
    private AiConfigService aiConfigService;

    /** AI 调用模板（统一「调用 → 解析 → 降级」骨架） */
    @Resource
    private AiInvoker aiInvoker;

    @Resource
    private AiChatFallback chatFallback;

    /** Agnes 上游客户端（流式问答直连，非流式仍走 AiInvoker 模板） */
    @Resource
    private AgnesClient agnesClient;

    /** AI 指标（流式缓存命中/未命中沿用与非流式一致的计数口径） */
    @Resource
    private AiMetrics aiMetrics;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ClassroomMapper classroomMapper;

    /** 模型 JSON 输出容错解析（统一解析入口，见 AiJsonSupport） */
    @Resource
    private AiJsonSupport aiJsonSupport;

    /** AI 结果缓存（复用既有 RedisCache；命中即免上游调用） */
    @Resource
    private AiResultCache aiResultCache;

    /** 提问文本最大长度（M11 修复：防超长输入放大 token 消耗与超时概率） */
    private static final int QUESTION_MAX_LENGTH = 200;

    /** R13-3 多轮上下文：最多携带历史消息条数（5 轮问答） */
    private static final int HISTORY_MAX_MESSAGES = 10;

    /** R13-3 历史中 user 消息最大长度（与当前问题上限一致） */
    private static final int HISTORY_USER_MAX_LENGTH = 200;

    /** R13-3 历史中 assistant 消息最大长度（防止长答案回灌放大 token） */
    private static final int HISTORY_ASSISTANT_MAX_LENGTH = 500;

    @Override
    public AiChatVO chat(String question, List<AiChatHistoryMessage> history) {
        // M11 修复：AI 入参长度上限（流式/非流式共用 validateQuestion，口径保持一致）
        validateQuestion(question);
        // 双开关关闭：返回友好提示，不报 500（前端隐藏悬浮助手）
        if (!aiConfigService.isAiEnabled()) {
            return AiChatVO.disabled();
        }
        // R13-3：历史归一化（角色白名单 + 条数/长度裁剪），不信任前端
        List<AgnesClient.ChatMessage> normalizedHistory = normalizeHistory(history);

        // 1. 尝试大模型问答（检索增强注入个人预约上下文）；失败/非法输出统一由 AiInvoker 降级
        String userMessage = buildContext(UserContext.getUserId()) + "\n【用户问题】" + question;
        // 结果缓存：Key 含 userId + 历史指纹 + 问题（答案注入了个人预约上下文，故不跨用户复用；
        // 不同上下文的相同问题不共享缓存）；TTL 取 30s（上下文会变）
        String cacheKey = aiResultCache.buildKey(
                AiConstants.AI_CACHE_NS_CHAT,
                AiPrompts.versionOf(AiPrompts.CHAT) + "-" + aiConfigService.getEffectiveModel(),
                AiHash.sha256Prefix8(UserContext.getUserId() + ":" + historyFingerprint(normalizedHistory) + ":" + question));
        // M7 修复：传入当前用户 ID，限流按用户维度隔离；R13-3：透传多轮历史
        return aiInvoker.invoke("问答", UserContext.getUserId(), AiPrompts.CHAT, normalizedHistory, userMessage,
                this::parseModelOutput,
                vo -> {
                    vo.setEnabled(true);
                    return vo;
                },
                // 2. 降级：关键词匹配 FAQ 库（未命中返回场景外预设话术；降级原因由 AiInvoker 注入 message）
                reason -> {
                    AiChatVO vo = new AiChatVO();
                    vo.setEnabled(true);
                    vo.setMessage(reason);
                    vo.setAnswer(chatFallback.chatFallback(question));
                    return vo;
                },
                // 3. 结果缓存规格（仅成功分支入缓存，降级结果不缓存）
                new AiInvoker.AiCacheSpec<>(cacheKey, AiConstants.AI_CACHE_TTL_CHAT_SECONDS,
                        new TypeReference<AiChatVO>() {
                        }));
    }

    @Override
    public void validateQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            throw new BusinessException("请输入您的问题");
        }
        if (question.length() > QUESTION_MAX_LENGTH) {
            throw new BusinessException("问题过长（不超过 " + QUESTION_MAX_LENGTH + " 字）");
        }
    }

    @Override
    public void streamChat(Long userId, String question, List<AiChatHistoryMessage> history, ChatStreamSink sink) {
        // R13-3：历史归一化（AI 关闭分支虽不使用历史，但归一化是纯函数，提前执行无副作用）
        List<AgnesClient.ChatMessage> normalizedHistory = normalizeHistory(history);
        // 1. AI 双开关关闭：meta 告知后直接 done（不查缓存、不调上游）
        if (!aiConfigService.isAiEnabled()) {
            try {
                sink.meta(false, true, AiConstants.AI_DISABLED_MESSAGE);
                sink.done(AiConstants.FINISH_REASON_DISABLED);
            } catch (IOException e) {
                log.debug("流式问答客户端已断开（AI 关闭分支）：{}", e.getMessage());
            }
            return;
        }

        // 2. 缓存值类型与非流式同为 AiChatVO；但版本段取 CHAT_STREAM（纯文本 Prompt），
        // 与非流式 CHAT(JSON) 的版本哈希不同，两路缓存各自独立、不共享
        String cacheKey = aiResultCache.buildKey(
                AiConstants.AI_CACHE_NS_CHAT,
                AiPrompts.versionOf(AiPrompts.CHAT_STREAM) + "-" + aiConfigService.getEffectiveModel(),
                AiHash.sha256Prefix8(userId + ":" + historyFingerprint(normalizedHistory) + ":" + question));
        AiChatVO cached = aiResultCache.get(cacheKey, new TypeReference<AiChatVO>() {
        });
        if (cached != null && StrUtil.isNotBlank(cached.getAnswer())) {
            aiMetrics.recordCacheHit();
            try {
                sink.meta(true, false, null);
                emitChunks(sink, cached.getAnswer());
                sink.done(AiConstants.FINISH_REASON_STOP);
            } catch (IOException e) {
                log.debug("流式问答客户端已断开（缓存重放分支）：{}", e.getMessage());
            }
            return;
        }
        aiMetrics.recordCacheMiss();

        // 3. 调上游：onReady 于收到 200 响应头时发出全流唯一 meta；建链后再降级（空内容/读体中断）不重发 meta；
        // delta 即收即发（规格 §3.2：meta 每流仅 1 个，且先于一切 delta）
        String userMessage = buildContext(userId) + "\n【用户问题】" + question;
        // 客户端断开后无需再回调：closed 标志阻止 Consumer 向已关闭连接写数据
        final boolean[] closed = {false};
        // onReady 是否成功发出过 meta：决定降级收尾能否再发 degraded meta
        final boolean[] readyCalled = {false};
        // 成功分支累积全文（仅用于写缓存；前端显示的是已实时发出的 delta，不受围栏剥离影响）
        final StringBuilder fullText = new StringBuilder();
        Runnable onReady = () -> {
            try {
                sink.meta(true, false, null);
                // meta 发送成功后才置位：meta 抛 IOException 的 closed 路径保持 readyCalled=false 不变
                readyCalled[0] = true;
            } catch (IOException e) {
                // 中断契约：包装为 UncheckedIOException 快速中止上游读取，AgnesClient 据此返回 aborted 句柄
                closed[0] = true;
                throw new UncheckedIOException(e);
            }
        };
        Consumer<String> onDelta = piece -> {
            if (closed[0]) {
                return;
            }
            fullText.append(piece);
            try {
                sink.delta(piece);
            } catch (IOException e) {
                // 中断契约：包装为 UncheckedIOException 快速中止上游读取，AgnesClient 据此返回 aborted 句柄
                closed[0] = true;
                throw new UncheckedIOException(e);
            }
        };
        AgnesClient.StreamChatHandle handle =
                agnesClient.chatStream(userId, AiPrompts.CHAT_STREAM, normalizedHistory, userMessage, onReady, onDelta);
        if (closed[0]) {
            // aborted 句柄静默结束：不发降级 meta、不推 FAQ、不写缓存、不发 done/error
            log.debug("流式问答客户端已断开（上游读取分支）");
            return;
        }

        if (handle.ok()) {
            // 4. 成功：已实时发出的 delta 不可回收，围栏剥离只作用于「写缓存的值」，不改写已显示文本
            String answer = AiStreamText.stripCodeFence(fullText.toString());
            AiChatVO vo = new AiChatVO();
            vo.setEnabled(true);
            vo.setAnswer(answer);
            aiResultCache.put(cacheKey, vo, AiConstants.AI_CACHE_TTL_CHAT_SECONDS);
            try {
                sink.done(StrUtil.blankToDefault(handle.finishReason(), AiConstants.FINISH_REASON_UNKNOWN));
            } catch (IOException e) {
                log.debug("流式问答 done 发送失败：{}", e.getMessage());
            }
            return;
        }

        // 5. 降级收尾：按 onReady 是否已发过唯一 meta 分流（规格 §3.2：meta 每流仅 1 个，先于一切 delta）
        try {
            if (!readyCalled[0]) {
                // 5a. 建链前降级（无 key/限流/非 200/建链异常）：尚未发过 meta，原因随 degraded meta 透出；
                // 本地 FAQ 全文按固定步长打字机推送
                sink.meta(true, true, handle.reason());
                emitChunks(sink, chatFallback.chatFallback(question));
                sink.done(AiConstants.FINISH_REASON_FALLBACK);
            } else if (fullText.length() == 0) {
                // 5b. 200 建链后空内容/全脏帧：唯一 meta 已在 onReady 发出，不能重发；
                // 降级原因无法再通过 meta 透出（meta 契约每流仅 1 个），finishReason=fallback 如实反映兜底内容
                emitChunks(sink, chatFallback.chatFallback(question));
                sink.done(AiConstants.FINISH_REASON_FALLBACK);
            } else {
                // 5c. 200 建链后读体中断、已有半截 delta 实时发出：不重发 meta、不拼 FAQ
                // （规格 §3.2 meta 唯一性；半截真实文本优于拼接 FAQ），done 如实标注结束原因
                sink.done(StrUtil.blankToDefault(handle.finishReason(), AiConstants.FINISH_REASON_UNKNOWN));
            }
        } catch (IOException e) {
            log.debug("流式问答收尾失败：{}", e.getMessage());
        }
    }

    /** 分段推送整段文本（缓存重放 / 降级 FAQ 共用） */
    private void emitChunks(ChatStreamSink sink, String text) throws IOException {
        for (String chunk : AiStreamText.chunks(text, AiConstants.AI_STREAM_CHUNK_SIZE)) {
            sink.delta(chunk);
        }
    }

    /**
     * 多轮历史归一化（R13-3，后端不信任前端的第二道边界）：
     * 1) null/空白 content 与非 user/assistant 角色直接丢弃；
     * 2) 单条超长保留开头截断（user 200 字 / assistant 500 字）；
     * 3) 总条数超过 10 条时保留最近 10 条（更早的对话对当前问题价值最低）。
     */
    private List<AgnesClient.ChatMessage> normalizeHistory(List<AiChatHistoryMessage> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<AgnesClient.ChatMessage> valid = new ArrayList<>();
        for (AiChatHistoryMessage message : raw) {
            if (message == null || StrUtil.isBlank(message.getRole()) || StrUtil.isBlank(message.getContent())) {
                continue;
            }
            String role = message.getRole().trim();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            int maxLength = "user".equals(role) ? HISTORY_USER_MAX_LENGTH : HISTORY_ASSISTANT_MAX_LENGTH;
            String content = message.getContent().trim();
            if (content.length() > maxLength) {
                content = content.substring(0, maxLength);
            }
            valid.add(new AgnesClient.ChatMessage(role, content));
        }
        if (valid.size() > HISTORY_MAX_MESSAGES) {
            return List.copyOf(valid.subList(valid.size() - HISTORY_MAX_MESSAGES, valid.size()));
        }
        return List.copyOf(valid);
    }

    /**
     * 历史指纹（R13-3）：归一化后的历史按 role:content 串联后取短哈希，并入缓存 key；
     * 空历史固定 "none"，使「不带历史」的 key 段稳定可预期。
     */
    private String historyFingerprint(List<AgnesClient.ChatMessage> normalizedHistory) {
        if (normalizedHistory == null || normalizedHistory.isEmpty()) {
            return "none";
        }
        String joined = normalizedHistory.stream()
                .map(message -> message.role() + ":" + message.content())
                .collect(Collectors.joining("|"));
        return AiHash.sha256Prefix8(joined);
    }

    /**
     * 构建个人上下文（检索增强：最近 5 条预约 + 预约总数），供模型回答个人记录类问题
     */
    private String buildContext(Long userId) {
        if (userId == null) {
            return "【用户信息】未获取到当前登录用户";
        }
        List<Reservation> recent = reservationMapper.selectList(
                new LambdaQueryWrapper<Reservation>()
                        .eq(Reservation::getUserId, userId)
                        .orderByDesc(Reservation::getCreateTime)
                        .last("LIMIT 5"));
        Long total = reservationMapper.selectCount(
                new LambdaQueryWrapper<Reservation>().eq(Reservation::getUserId, userId));

        StringBuilder sb = new StringBuilder();
        sb.append("【用户预约概况】共 ").append(total).append(" 条预约记录");
        if (recent.isEmpty()) {
            sb.append("；暂无预约记录");
            return sb.toString();
        }
        // 教室名称映射（避免 N+1）
        List<Long> roomIds = recent.stream().map(Reservation::getClassroomId).distinct().toList();
        Map<Long, String> roomNameMap = roomIds.isEmpty() ? Map.of()
                : classroomMapper.selectBatchIds(roomIds).stream()
                        .collect(Collectors.toMap(Classroom::getId, Classroom::getName));
        sb.append("；最近预约：");
        sb.append(recent.stream()
                .map(r -> roomNameMap.getOrDefault(r.getClassroomId(), "教室" + r.getClassroomId())
                        + " " + r.getReserveDate()
                        + " " + TimeUtil.formatTime(r.getStartTime()) + "-" + TimeUtil.formatTime(r.getEndTime())
                        + "（" + statusText(r.getStatus()) + "）")
                .collect(Collectors.joining("；")));
        return sb.toString();
    }

    /** 状态文案（与前端三态标签口径一致） */
    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Constants.RES_STATUS_PENDING -> "待审核";
            case Constants.RES_STATUS_APPROVED -> "已通过";
            case Constants.RES_STATUS_REJECTED -> "已驳回";
            case Constants.RES_STATUS_CANCELED -> "已取消";
            default -> "未知";
        };
    }

    /**
     * 解析大模型 JSON 输出（{"answer":"..."}），非法返回 null（触发降级）
     */
    private AiChatVO parseModelOutput(String content) {
        try {
            JsonNode root = aiJsonSupport.readObject(content);
            if (root == null) {
                return null;
            }
            if (!root.hasNonNull("answer") || StrUtil.isBlank(root.get("answer").asText())) {
                return null;
            }
            AiChatVO vo = new AiChatVO();
            vo.setAnswer(root.get("answer").asText());
            return vo;
        } catch (Exception e) {
            log.warn("问答模型输出 JSON 解析失败：{}", e.getMessage());
            return null;
        }
    }
}
