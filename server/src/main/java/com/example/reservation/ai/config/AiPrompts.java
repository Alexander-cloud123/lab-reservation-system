package com.example.reservation.ai.config;

/**
 * AI Prompt 内置模板注册表（Prompt 归属统一的单一来源）
 *
 * 背景（Prompt 归属不一致）：
 *  - 解析 / 合规两个 Prompt 取自 ai_config（prompt_parse / prompt_compliance），可动态调整；
 *  - 推荐 / 问答两个 Prompt 此前硬编码在各自 ServiceImpl 的私有常量里，既无法动态调整，也无处统一校验。
 * 本类把所有「内置兜底模板」集中到一处，并声明每个 Prompt 约定的顶层输出键，
 * 供 {@link AiPromptSelfCheck} 做启动自检（校验与 AgnesClient 的 response_format=json_object 形态一致）。
 *
 * 说明：本类只提供内置兜底值，不写库；ai_config 中的 DB 模板（若已配置）优先。
 * 是否把 prompt_chat / prompt_recommend 也纳入 ai_config，涉及表数据变更，须经负责人确认后再做。
 *
 * @author reservation-team
 */
public final class AiPrompts {

    private AiPrompts() {
    }

    /* ===== 各 Prompt 约定的顶层输出键（response_format=json_object 要求顶层必须是对象）===== */
    /** 解析 Prompt 顶层键：date */
    public static final String PARSE_ROOT_KEY = "date";
    /** 合规 Prompt 顶层键：compliant */
    public static final String COMPLIANCE_ROOT_KEY = "compliant";
    /** 问答 Prompt 顶层键：answer */
    public static final String CHAT_ROOT_KEY = "answer";
    /** 推荐 Prompt 顶层键：recommendations */
    public static final String RECOMMEND_ROOT_KEY = "recommendations";

    /** 自然语言预约解析：结构化 JSON（ai_config.prompt_parse 未配置时的内置兜底） */
    public static final String PARSE = "你是教室预约解析器，只输出JSON："
            + "{\"date\":\"YYYY-MM-DD\",\"startTime\":\"HH:mm\",\"endTime\":\"HH:mm\","
            + "\"capacity\":int,\"roomType\":\"普通教室|实验室|机房|null\",\"purpose\":\"string\"}";

    /** 预约合规校验：compliant + reason（ai_config.prompt_compliance 未配置时的内置兜底） */
    public static final String COMPLIANCE = "判断预约用途是否合规（是否与教学/实验/自习/竞赛等正当用途相关），"
            + "输出{\"compliant\":true|false,\"reason\":\"string\"}";

    /**
     * 预约智能问答：场景限定 + 结构化 JSON 输出
     * 2026-09-13 AI 演示准备轮修复：原版「与预约无关的问题统一回复」被 flash 模型理解为一切问题的默认输出
     * （实测场景内问题三次全部拒答）；改为「业务规则内联 + 仅完全无关问题才回复抱歉」，模型有据可答。
     */
    public static final String CHAT = "你是高校教室预约管理系统的智能助手，请依据以下业务规则回答用户的问题：\n"
            + "- 取消预约：待审核或已通过的预约可在预约开始前 1 小时自由取消（在我的预约页点击取消，需二次确认）；开始前不足 1 小时不可取消，特殊情况请联系管理员。\n"
            + "- 预约冲突：同一教室同一日期下，新预约时段与已通过预约时段存在重叠即判定冲突（前后端双重校验）。\n"
            + "- 审核流程：学生提交预约后状态为待审核，管理员在预约审核页通过或驳回（驳回会填写审核备注）；结果可在我的预约页查看。\n"
            + "- 预约状态：待审核（提交后）、已通过（管理员通过）、已驳回（不可修改）、已取消（用户取消）。\n"
            + "- 预约步骤：教室列表 → 点击教室卡片进入详情 → 选择日期与时段 → 填写用途 → 提交预约（实时冲突校验）；也可用「AI 快速预约」直接描述需求。\n"
            + "- 教室信息：教室列表支持按关键词/楼栋/类型/日期筛选，卡片展示实时状态（空闲/使用中/已结束）与容量、设备；详情页可查看当日时段占用并预约。\n"
            + "请回答与教室预约相关的任何问题（预约、取消、审核、状态、教室信息、个人记录等）。只有当问题与教室预约完全无关（如天气、美食、娱乐）时，才回复：抱歉，我只能解答预约相关问题。\n"
            + "示例：用户问“我怎么取消预约？”，回答：{\"answer\":\"待审核或已通过的预约可在预约开始前 1 小时自由取消，在我的预约页点击取消并二次确认即可；开始前不足 1 小时不可取消。\"}\n"
            + "示例：用户问“预约审核多久有结果？”，回答：{\"answer\":\"学生提交预约后状态为待审核，管理员在预约审核页通过或驳回，结果可在我的预约页查看。\"}\n"
            + "请用简洁的中文回答，并以JSON格式输出：{\"answer\":\"回答内容\"}";

    /**
     * 智能教室推荐：候选排序 + 推荐理由
     * 注意：AgnesClient 以 response_format={"type":"json_object"} 调用，结构化输出模式要求**顶层必须是 JSON 对象**，
     * 因此这里必须约定顶层对象形态；此前 Prompt 要求顶层数组与客户端 json_mode 相矛盾，
     * 导致模型返回单对象被判非法而 100% 降级（P2 缺陷 #5）。
     */
    public static final String RECOMMEND = "你是高校教室预约管理系统的智能推荐助手。"
            + "根据用户的历史预约偏好与候选教室的明日空闲情况，从候选教室中选择最合适的 3 间，"
            + "按匹配度从高到低排序，并为每间生成一句话中文推荐理由。"
            + "只能从候选教室的 classroomId 中选择。"
            + "只输出一个 JSON 对象，不要输出数组、不要输出解释文字、不要使用 markdown 代码块，格式严格为："
            + "{\"recommendations\":[{\"classroomId\":数字,\"reason\":\"一句话理由\"}]}";
}