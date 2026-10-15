package com.example.reservation.ai.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式问答文本工具（R12）
 *  - chunks：把整段答案按固定步长切成多个 delta（降级 FAQ 分段推送 / 缓存命中重放）；
 *  - stripCodeFence：纯文本 Prompt 偶发被模型用 ``` 包裹时的最小兜底，只去整体围栏、绝不改写正文。
 *
 * @author reservation-team
 */
public final class AiStreamText {

    private AiStreamText() {
    }

    /**
     * 按固定字符步长切分文本（不区分中英文/code unit，按 char 切）
     *
     * @param text 原文；空白/null 返回空列表
     * @param size 每段字符数（&lt;=0 按 1 兜底）
     */
    public static List<String> chunks(String text, int size) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        int step = Math.max(size, 1);
        List<String> result = new ArrayList<>((text.length() + step - 1) / step);
        for (int i = 0; i < text.length(); i += step) {
            result.add(text.substring(i, Math.min(i + step, text.length())));
        }
        return result;
    }

    /**
     * 去除整体 Markdown 代码围栏（```lang\n...\n```）；不构成完整围栏时原样返回。
     * 注意：仅供流式成功后写入缓存前对「累积全文」使用；已实时发出的 delta 不可回收，不受本方法影响。
     */
    public static String stripCodeFence(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        if (!trimmed.startsWith("```")) {
            return text;
        }
        int firstNl = trimmed.indexOf('\n');
        if (firstNl < 0) {
            // 只有围栏头、没有内容与收尾，属于误伤高风险形态，不处理
            return text;
        }
        String body = trimmed.substring(firstNl + 1);
        if (!body.endsWith("```")) {
            // 有围栏头但无收尾（max_tokens 截断 / 正文恰以 ``` 开头），不构成完整围栏，原样返回
            return text;
        }
        return body.substring(0, body.length() - 3).strip();
    }
}
