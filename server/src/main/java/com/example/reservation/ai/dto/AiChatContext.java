package com.example.reservation.ai.dto;

import lombok.Data;

/**
 * 问答请求附带的页面上下文（R15-3）。
 *
 * <p>只接受结构化字段，不接受客户端拼接的任意文本：上下文会进入模型 Prompt，
 * 若允许自由文本等于开了一个 Prompt 注入入口。当前只有「用户正在查看的教室」一个字段，
 * 服务端二次校验存在性与启停状态后才使用，查不到就静默忽略。
 *
 * @author hyr
 */
@Data
public class AiChatContext {

    /** 用户当前正在查看的教室 ID（可选；为空或非正数视为无上下文） */
    private Long classroomId;
}
