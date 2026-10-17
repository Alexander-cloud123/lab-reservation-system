package com.example.reservation.ai.dto;

import lombok.Data;

/**
 * 多轮对话历史消息（R13-3，POST /api/ai/chat 与 /api/ai/chat?stream=true 的可选字段 history 元素）
 * 仅承载会话内上下文：不落库、不建表，刷新即清空；后端会做角色白名单、条数与长度二次裁剪。
 *
 * @author reservation-team
 */
@Data
public class AiChatHistoryMessage {

    /** 角色：仅允许 user / assistant，其余值在归一化时丢弃 */
    private String role;

    /** 消息内容（user 上限 200 字、assistant 上限 500 字，超长保留开头截断） */
    private String content;
}
