package com.example.reservation.common;

/**
 * 系统常量类（魔法值统一收敛，禁止散落硬编码）
 *
 * @author reservation-team
 */
public final class Constants {

    private Constants() {
    }

    /* ===== 角色 ===== */
    /** 角色：学生 */
    public static final int ROLE_STUDENT = 0;
    /** 角色：管理员 */
    public static final int ROLE_ADMIN = 1;

    /* ===== 用户状态 ===== */
    /** 用户状态：禁用 */
    public static final int USER_STATUS_DISABLED = 0;
    /** 用户状态：正常 */
    public static final int USER_STATUS_NORMAL = 1;

    /* ===== 预约状态（状态流转：待审核0→已通过1/已驳回2；待审核0/已通过1→已取消3）===== */
    /** 预约状态：待审核 */
    public static final int RES_STATUS_PENDING = 0;
    /** 预约状态：已通过 */
    public static final int RES_STATUS_APPROVED = 1;
    /** 预约状态：已驳回 */
    public static final int RES_STATUS_REJECTED = 2;
    /** 预约状态：已取消 */
    public static final int RES_STATUS_CANCELED = 3;

    /* ===== 教室状态 ===== */
    /** 教室状态：停用 */
    public static final int CLASSROOM_STATUS_DISABLED = 0;
    /** 教室状态：可用 */
    public static final int CLASSROOM_STATUS_ENABLED = 1;

    /* ===== Token 鉴权 ===== */
    /** 请求头名称 */
    public static final String TOKEN_HEADER = "Authorization";
    /** Token 前缀 */
    public static final String TOKEN_PREFIX = "Bearer ";

    /* ===== 管理员专属接口前缀（拦截器按前缀校验角色）=====
     * R7 权限口径（负责人已确认）：/api/ai 从管理员前缀移除——AI 接口「登录即可」，
     * 学生端（推荐/解析/助手）与管理端（合规校验）均需访问，且 AI 只读不写。
     * 既有接口权限矩阵（user/classroom/reservation manage、export、stats）不变。 */
    public static final String[] ADMIN_API_PREFIXES = {
            "/api/user/manage",
            "/api/classroom/manage",
            "/api/reservation/manage",
            "/api/reservation/export",
            "/api/stats"
    };

    /** 密码最小长度 */
    public static final int PASSWORD_MIN_LENGTH = 6;

    /** 管理员重置密码默认值（R2 用户管理：重置后以该密码登录，BCrypt 加密存储） */
    public static final String DEFAULT_PASSWORD = "123456";

    /* ===== 教室类型 ===== */
    /** 教室类型：普通教室 */
    public static final int CLASSROOM_TYPE_NORMAL = 1;
    /** 教室类型：实验室 */
    public static final int CLASSROOM_TYPE_LAB = 2;
    /** 教室类型：机房 */
    public static final int CLASSROOM_TYPE_COMPUTER = 3;

    /* ===== 预约核心规则（R3）===== */
    /** 取消预约时限：预约开始前 N 小时内禁止取消（需求文档 1.4：不足 1 小时不可取消） */
    public static final int RESERVATION_CANCEL_HOURS = 1;

    /* ===== 教室实时状态标签（R3 口径 + R4 补全已结束态）=====
     * 口径：当天存在已通过预约且当前时刻 ∈ [开始,结束) → 使用中；
     *      当天存在已通过预约且当前时刻 ≥ 全部时段结束时间 → 已结束；其余 → 当前空闲 */
    /** 实时状态标签：当前空闲 */
    public static final String STATUS_LABEL_FREE = "当前空闲";
    /** 实时状态标签：使用中 */
    public static final String STATUS_LABEL_IN_USE = "使用中";
    /** 实时状态标签：已结束（当天已通过预约全部结束，R4 补全） */
    public static final String STATUS_LABEL_ENDED = "已结束";

    /* ===== 收藏（R4 收藏全链路）===== */
    /** 常用教室收藏上限（需求文档 1.4：每人最多收藏 10 间教室） */
    public static final int FAVORITE_MAX_COUNT = 10;

    /* ===== 管理员专属接口路径（R3：审核接口不在 manage 前缀下，拦截器精确匹配）===== */
    /** 预约批量审核路径前缀 */
    public static final String RESERVATION_BATCH_AUDIT_PATH = "/api/reservation/batch-audit";
    /** 预约单条审核路径正则：/api/reservation/{id}/audit */
    public static final String RESERVATION_AUDIT_PATH_REGEX = "^/api/reservation/\\d+/audit$";

    /* ===== 日历总览（R5 亮点功能，需求文档 2.4 第 7 页）===== */
    /** 日历查询最大跨度（天）：防止一次性拉取超大区间 */
    public static final int CALENDAR_MAX_DAYS = 366;
    /** 日历区间查询：startDate / endDate 必填，非法或缺参由接口返回 400 */
    public static final String CALENDAR_START_REQUIRED_MSG = "日历查询必须指定开始日期 startDate";
    public static final String CALENDAR_END_REQUIRED_MSG = "日历查询必须指定结束日期 endDate";
    public static final String CALENDAR_RANGE_MSG = "日期区间跨度不能超过 366 天";

    /* ===== 数据看板（R5 亮点功能，需求文档 2.4 第 13 页）===== */
    /** 每日可预约时长（小时）：08:00-22:00，教室使用率分母口径 */
    public static final int DAILY_AVAILABLE_HOURS = 14;
    /** 每日可预约开始时间 */
    public static final String DAILY_AVAILABLE_START = "08:00";
    /** 每日可预约结束时间 */
    public static final String DAILY_AVAILABLE_END = "22:00";
    /** 看板时间筛选默认跨度（天）：缺省近 30 天 */
    public static final int STATS_DEFAULT_DAYS = 30;
    /** 看板统计查询最大跨度（天） */
    public static final int STATS_MAX_DAYS = 366;
    /** 热门时段分布分桶标签（按开始时间归属；超出桶范围归「其他」） */
    public static final String[] TIME_SLOT_LABELS = {
            "08:00-10:00", "10:00-12:00", "14:00-16:00", "16:00-18:00", "19:00-21:00", "其他"
    };
    /** 热门时段分布分桶数（含「其他」桶） */
    public static final int TIME_SLOT_BUCKET_COUNT = 6;
}
