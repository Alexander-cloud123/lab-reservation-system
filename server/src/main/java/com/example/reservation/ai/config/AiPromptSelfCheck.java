package com.example.reservation.ai.config;

import cn.hutool.core.util.StrUtil;
import com.example.reservation.ai.service.AiConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AI Prompt 启动自检（只读校验，不修改任何配置或业务行为）
 *
 * 校验动机：AgnesClient 一律以 response_format={"type":"json_object"} 调用，该模式要求模型顶层输出必须是 JSON 对象。
 * 若某个 Prompt 约定的却是顶层数组，两者自相矛盾，模型输出会被判定为非法而 100% 降级
 * （历史上推荐接口即因此长期降级，P2 缺陷 #5）。启动时把这条隐性约定变成显式校验，避免回归。
 *
 * 每个 Prompt 的校验口径：其有效文本（ai_config 有值取 DB 值，否则取 {@link AiPrompts} 内置兜底）
 * 必须包含与 json_object 一致的顶层对象示例——即出现 {\"约定键\" 骨架。
 *
 * 输出（仅日志，全部为可观测信息，不影响启动）：
 *  - 通过：INFO 记录来源（ai_config / 内置兜底）与顶层键；
 *  - 不一致：WARN 提示 Prompt 缺少顶层对象示例，可能引发降级；
 *  - 归属报告：INFO 汇总「已纳入 ai_config」与「仍为内置」的 Prompt 清单（Prompt 归属不一致的现状可见化）。
 *
 * @author reservation-team
 */
@Slf4j
@Component
public class AiPromptSelfCheck implements ApplicationRunner {

    @Resource
    private AiConfigService aiConfigService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<String> dbBacked = new ArrayList<>();
            List<String> builtinOnly = new ArrayList<>();

            check(AiConstants.CONFIG_KEY_PROMPT_PARSE, "解析 Prompt",
                    aiConfigService.getPromptParse(), AiPrompts.PARSE, AiPrompts.PARSE_ROOT_KEY, dbBacked, builtinOnly);
            check(AiConstants.CONFIG_KEY_PROMPT_COMPLIANCE, "合规 Prompt",
                    aiConfigService.getPromptCompliance(), AiPrompts.COMPLIANCE, AiPrompts.COMPLIANCE_ROOT_KEY, dbBacked, builtinOnly);
            check(null, "问答 Prompt",
                    null, AiPrompts.CHAT, AiPrompts.CHAT_ROOT_KEY, dbBacked, builtinOnly);
            check(null, "推荐 Prompt",
                    null, AiPrompts.RECOMMEND, AiPrompts.RECOMMEND_ROOT_KEY, dbBacked, builtinOnly);

            // Prompt 归属现状可见化：DB 化与内置并存的清单（是否需要统一为全 DB，待负责人确认后再做）
            log.info("[AI 自检] Prompt 归属：已纳入 ai_config = {}；仍为内置 = {}", dbBacked, builtinOnly);
        } catch (Exception e) {
            // 自检仅为可观测性，任何异常都不应影响应用启动
            log.warn("[AI 自检] Prompt 一致性校验异常（不影响启动）：{}", e.getMessage());
        }
    }

    /**
     * 校验单个 Prompt 是否声明了与 json_object 一致的顶层对象示例
     *
     * @param dbKey       ai_config 配置键（内置 Prompt 传 null）
     * @param label       人类可读名称
     * @param dbValue     ai_config 中的模板值（可为 null）
     * @param builtin     内置兜底模板
     * @param rootKey     约定的顶层输出键
     * @param dbBacked    出参：已纳入 ai_config 的 Prompt 名称
     * @param builtinOnly 出参：仍为内置的 Prompt 名称
     */
    private void check(String dbKey, String label, String dbValue, String builtin, String rootKey,
                       List<String> dbBacked, List<String> builtinOnly) {
        boolean fromDb = StrUtil.isNotBlank(dbValue);
        String effective = fromDb ? dbValue : builtin;
        String source = fromDb ? "ai_config" : "内置兜底";
        if (fromDb) {
            dbBacked.add(label + "(" + dbKey + ")");
        } else {
            builtinOnly.add(label);
        }
        if (declaresArrayRoot(effective)) {
            // 与 json_object 直接冲突：模型将被强制输出顶层对象，而 Prompt 要求顶层数组 → 必然解析失败、持续降级
            log.warn("[AI 自检] {} 约定的是顶层 JSON 数组（来源：{}），与 response_format=json_object（要求顶层必须是对象）冲突，"
                    + "模型输出会被判非法而持续降级，请改为顶层对象 {\"{}\":...}", label, source, rootKey);
        } else if (!declaresObjectRoot(effective, rootKey)) {
            log.warn("[AI 自检] {} 未包含顶层对象示例（来源：{}，应出现 {\"{}\":...}），无法确认与 json_object 一致，"
                    + "结构化输出可能不匹配而触发降级，请检查 Prompt 约定", label, source, rootKey);
        } else {
            log.info("[AI 自检] {} 校验通过（来源：{}，顶层对象键：{}）", label, source, rootKey);
        }
    }

    /** 是否声明了顶层对象示例：出现 {\"约定键\"（允许 { 与 " 之间有空白） */
    private boolean declaresObjectRoot(String prompt, String rootKey) {
        return Pattern.compile("\\{\\s*\"" + Pattern.quote(rootKey) + "\"").matcher(prompt).find();
    }

    /**
     * 是否约定顶层 JSON 数组：Prompt 中首个 JSON 骨架起始符是 '['（即 '[' 出现在 '{' 之前）。
     * 仅针对「{ 与 [ 的相对位置」判断，不解析完整 JSON，因此无法识别所有畸形 Prompt；
     * 作为启动期提示，宁可多提示一次人工复核，也不放过历史上真实发生过的顶层数组冲突。
     */
    private boolean declaresArrayRoot(String prompt) {
        int objectIndex = prompt.indexOf('{');
        int arrayIndex = prompt.indexOf('[');
        return arrayIndex >= 0 && (objectIndex < 0 || arrayIndex < objectIndex);
    }
}