package com.example.reservation.ai.config;

/**
 * AI 模块常量（R7：魔法值统一收敛，禁止散落硬编码）
 * 覆盖 ai_config 配置键、AI 开关消息、降级引擎话术等
 *
 * @author reservation-team
 */
public final class AiConstants {

    private AiConstants() {
    }

    /* ===== ai_config 配置键（表结构基线与五行为 R1 既定，只读不改）===== */
    /** 配置键：AI 总开关（动态开关，与 application.yml ai.enable 组成双开关） */
    public static final String CONFIG_KEY_AI_ENABLE = "ai_enable";
    /** 配置键：AI 默认模型 */
    public static final String CONFIG_KEY_AI_MODEL = "ai_model";
    /** 配置键：合规校验本地违规关键词库（逗号分隔） */
    public static final String CONFIG_KEY_COMPLIANCE_KEYWORDS = "compliance_keywords";
    /** 配置键：自然语言预约解析 Prompt 模板 */
    public static final String CONFIG_KEY_PROMPT_PARSE = "prompt_parse";
    /** 配置键：预约合规校验 Prompt 模板 */
    public static final String CONFIG_KEY_PROMPT_COMPLIANCE = "prompt_compliance";

    /* ===== AI 开关消息 ===== */
    /** AI 关闭提示（双开关任一为 false 时返回，前端据此隐藏/禁用 AI 入口） */
    public static final String AI_DISABLED_MESSAGE = "AI 服务未启用，当前为纯预约系统模式";
    /** 限流降级提示 */
    public static final String AI_RATE_LIMITED_MESSAGE = "AI 请求过于频繁，已自动切换为本地规则模式";
    /** 密钥缺失降级提示 */
    public static final String AI_NO_KEY_MESSAGE = "AI 服务未配置密钥，已自动切换为本地规则模式";
    /** 服务异常降级提示 */
    public static final String AI_SERVICE_ERROR_MESSAGE = "AI 服务暂时不可用，已自动切换为本地规则模式";

    /* ===== 限流窗口（RPM 保护，spec.md 6.1：RPM≈20 次/分钟）===== */
    /** 限流统计窗口长度（毫秒）：固定窗口 1 分钟 */
    public static final long RATE_LIMIT_WINDOW_MS = 60_000L;

    /* ===== 自然语言解析降级（正则+关键词模板，spec.md 6.3）===== */
    /** 解析失败标志（与 Prompt 模板输出约定一致：{"error":"无法解析"}） */
    public static final String PARSE_ERROR = "无法解析";
    /** 教室类型文案：普通教室 */
    public static final String ROOM_TYPE_NORMAL = "普通教室";
    /** 教室类型文案：实验室 */
    public static final String ROOM_TYPE_LAB = "实验室";
    /** 教室类型文案：机房 */
    public static final String ROOM_TYPE_COMPUTER = "机房";

    /* ===== 场景限定问答（需求文档 1.4 AI 业务规则 1：无关问题返回预设话术）===== */
    /** 无关问题预设话术 */
    public static final String CHAT_OUT_OF_SCOPE = "抱歉，我只能解答高校教室预约系统的相关问题（如何预约/取消、教室信息、我的预约记录、审核状态等）。";

    /* ===== 智能推荐（spec.md 6.2 推荐口径）===== */
    /** 推荐目标日期偏移（天）：默认推荐明天（当日时段过半后意义降低，固定偏移保证可复跑） */
    public static final int RECOMMEND_DATE_OFFSET_DAYS = 1;
    /** 推荐教室数量 Top3 */
    public static final int RECOMMEND_TOP_COUNT = 3;
    /** 进入大模型排序的候选教室上限（规则预筛后） */
    public static final int RECOMMEND_CANDIDATE_LIMIT = 8;

    /* ===== 合规校验降级（ai_config.compliance_keywords 关键词规则判定）===== */
    /** 命中违规关键词时的原因前缀 */
    public static final String COMPLIANCE_HIT_PREFIX = "包含违规关键词：";
    /** 未命中时合规原因 */
    public static final String COMPLIANCE_OK_REASON = "用途符合教学/实验/自习/竞赛等正当用途要求";
}
