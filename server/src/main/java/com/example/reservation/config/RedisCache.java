package com.example.reservation.config;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis 统一封装（需求设计文档 2.2 第 145 行可选加分项）
 * 业务层只与本类交互，不直接散落 RedisTemplate 调用，职责：
 *  1. 登录会话：saveToken / validateToken / removeToken（支撑「可注销会话 / 单点会话」）；
 *  2. 业务缓存：getObject / setObject（对象以 JSON 文本存取，redis-cli 可读、无乱码）；
 *  3. 缓存失效：delete（单 Key）/ invalidateBusinessCaches（代次失效，O(1) 且无需 SCAN）。
 * 降级原则（AGENTS.md 4.4 风格对齐 AI 模块）：
 *  - redis.enable=false 时所有操作直接空转 / 视为跳过；
 *  - Redis 连接失败、命令超时、序列化异常等任何问题一律捕获，读操作回源、写操作空转，绝不向上抛出阻断业务；
 *  - 会话校验异常时返回 SKIP，由拦截器退化为「仅 JWT 校验」。
 *
 * @author reservation-team
 */
@Slf4j
@Component
public class RedisCache {

    /** 数据看板统计缓存 Key 前缀（完整 Key 追加 接口名:起止日期） */
    public static final String STATS_KEY_PREFIX = "cache:stats:";

    /** 学生端教室列表缓存 Key 前缀（完整 Key 追加 查询参数指纹） */
    public static final String CLASSROOM_LIST_KEY_PREFIX = "cache:classroom:list:";

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private ObjectMapper objectMapper;   // 与 Spring MVC 共用同一个主 mapper（Boot 自动配置）

    @Resource
    private RedisProperties redisProperties;

    /** Redis 功能总开关（关闭后业务完全不依赖 Redis） */
    public boolean isEnabled() {
        return redisProperties.isEnable();
    }

    /* ==================== 登录会话（Token） ==================== */

    /**
     * 登录成功后写入会话：同一 userId 固定 Key，新登录直接覆盖旧 Token（单点会话：旧会话立即失效）。
     * TTL 与 JWT 有效期一致，到期 Key 自动清除。
     */
    public void saveToken(Long userId, String token, long ttlSeconds) {
        if (!isEnabled() || userId == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(tokenKey(userId), token, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 写入失败不阻断登录：退化为无状态 JWT（降级）
            log.warn("Redis 写入登录 Token 失败，退化为无状态 JWT 校验：{}", e.getMessage());
        }
    }

    /**
     * 退出登录时删除会话 Key，登出后该 Token 立即失效。
     */
    public void removeToken(Long userId) {
        if (!isEnabled() || userId == null) {
            return;
        }
        try {
            redisTemplate.delete(tokenKey(userId));
        } catch (Exception e) {
            // 删除失败不阻断登出流程（降级）
            log.warn("Redis 删除登录 Token 失败，忽略：{}", e.getMessage());
        }
    }

    /**
     * 校验请求携带的 Token 是否仍是当前有效会话。
     *
     * @return VALID-命中且与当前会话一致；INVALID-Redis 正常但无此会话（已登出/被新登录顶掉）→ 应返回 401；
     *         SKIP-未启用或 Redis 异常 → 调用方退化为仅 JWT 校验，不阻断请求
     */
    public SessionStatus validateToken(Long userId, String presentedToken) {
        if (!isEnabled()) {
            return SessionStatus.SKIP;
        }
        try {
            String stored = redisTemplate.opsForValue().get(tokenKey(userId));
            if (stored == null) {
                // Redis 正常但会话不存在：已登出 / 会话过期 / 被单点登录覆盖
                return SessionStatus.INVALID;
            }
            return stored.equals(presentedToken) ? SessionStatus.VALID : SessionStatus.INVALID;
        } catch (Exception e) {
            // Redis 不可用：降级为仅 JWT 校验，不阻断业务
            log.warn("Redis 校验登录会话失败，退化为仅 JWT 校验：{}", e.getMessage());
            return SessionStatus.SKIP;
        }
    }

    /** 会话校验结果三态（区分「确实失效」与「缓存不可用需降级」） */
    public enum SessionStatus {
        /** 会话有效，Token 与 Redis 中一致 */
        VALID,
        /** 会话确实不存在 / 已被顶掉，应拒绝（401） */
        INVALID,
        /** 未启用或 Redis 异常，跳过 Redis 校验，退化为仅 JWT */
        SKIP
    }

    /* ==================== 业务对象缓存（JSON） ==================== */

    /**
     * 读取缓存对象并反序列化；未命中、未启用、异常、反序列化失败均返回 null（调用方回源查库，即降级）。
     */
    public <T> T getObject(String key, TypeReference<T> typeReference) {
        if (!isEnabled()) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null || json.isBlank()) {
                return null;
            }
            return objectMapper.readValue(json, typeReference);
        } catch (Exception e) {
            // 反序列化失败按缓存未命中处理，并顺手清除脏 Key，避免持续命中坏数据
            log.warn("Redis 读取缓存失败，回源查库（key={}）：{}", key, e.getMessage());
            delete(key);
            return null;
        }
    }

    /**
     * 写入缓存对象（JSON 文本）并设置 TTL；任何异常空转，不影响主流程。
     */
    public void setObject(String key, Object value, long ttlSeconds) {
        if (!isEnabled() || value == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 写入缓存失败（key={}）：{}", key, e.getMessage());
        }
    }

    /* ==================== 缓存失效 ==================== */

    /** 删除单个 Key（异常空转） */
    public void delete(String key) {
        if (!isEnabled()) {
            return;
        }
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis 删除缓存失败（key={}）：{}", key, e.getMessage());
        }
    }

    /**
     * 业务数据变更后统一失效：推进数据看板 + 教室列表两族缓存的代次。
     * 触发点：预约提交/审核/取消、教室增改/启停/删除（缓存一致性：写后失效，下次读回源重建）。
     *
     * <p>实现说明：两族缓存的 Key 都由「查询参数指纹」构成（看板按接口+日期区间、教室列表按分页筛选参数），
     * 不存在「受影响教室」这一维度可直接定位到 Key，因此失效语义仍是整族失效，但由
     * 「SCAN 遍历删除」改为「代次 +1」：
     * <ul>
     *   <li>失效开销恒为 O(1)，与缓存 Key 数量无关（原实现每次写操作两次 SCAN）；</li>
     *   <li>旧代次 Key 不再被读取，由 TTL（60s）自然过期回收，无需主动删除；</li>
     *   <li>天然免疫「删 Key 与并发回填交错」的竞态——并发回填只会写进旧代次 Key，永远不会被读到
     *       （原「先删后回填」实现中，回填晚于删除的请求会把脏数据留在缓存里直到 TTL 到期）。</li>
     * </ul>
     */
    public void invalidateBusinessCaches() {
        bumpGeneration(STATS_GEN_KEY);
        bumpGeneration(CLASSROOM_LIST_GEN_KEY);
    }

    /** 数据看板缓存代次 Key（完整 Key 形如 cache:stats:g3:usage-rate:起:止） */
    public static final String STATS_GEN_KEY = "cache:stats:gen";

    /** 学生端教室列表缓存代次 Key（完整 Key 形如 cache:classroom:list:v2:g3:分页筛选指纹） */
    public static final String CLASSROOM_LIST_GEN_KEY = "cache:classroom:list:gen";

    /**
     * 读取某族缓存当前代次（代次 Key 不存在 / 未启用 / 异常一律返回 0，调用方按代次 0 拼 Key）。
     */
    public long currentGeneration(String genKey) {
        if (!isEnabled()) {
            return 0L;
        }
        try {
            String v = redisTemplate.opsForValue().get(genKey);
            return StrUtil.isBlank(v) ? 0L : Long.parseLong(v.trim());
        } catch (Exception e) {
            // 读失败按代次 0 处理：Key 变化 → 视为未命中 → 回源查库（降级，不阻断业务）
            log.warn("Redis 读取缓存代次失败，按代次 0 处理（key={}）：{}", genKey, e.getMessage());
            return 0L;
        }
    }

    /**
     * 推进某族缓存代次（INCR，O(1)）：写操作后调用即完成该族缓存失效，无需 SCAN 遍历；异常空转。
     *
     * <p>代次 Key 不设 TTL：一旦回退（如被淘汰后 INCR 从 1 重新计数），旧代次 Key 可能被重新读取而复活脏数据。
     */
    public void bumpGeneration(String genKey) {
        if (!isEnabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().increment(genKey);
        } catch (Exception e) {
            // 推进失败不阻断业务：下次读可能仍命中旧缓存，但 TTL（60s）内自愈
            log.warn("Redis 推进缓存代次失败（key={}）：{}", genKey, e.getMessage());
        }
    }

    /* ==================== 分布式互斥锁（M4 修复：收藏上限"先查后写"的并发互斥） ==================== */

    /**
     * 尝试获取互斥锁（SETNX + TTL）：Redis 未启用或异常时降级为"视为获取成功"（不加锁，靠唯一索引兜底），不阻断业务。
     *
     * @return true=获取成功（或 Redis 不可用降级放行）；false=锁已被占用
     */
    public boolean tryLock(String key, long ttlSeconds) {
        if (!isEnabled()) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", ttlSeconds, TimeUnit.SECONDS));
        } catch (Exception e) {
            // Redis 异常：降级放行，靠数据库唯一索引兜底（降级纪律：Redis 不阻断核心业务）
            log.warn("Redis 获取互斥锁失败，降级放行（key={}）：{}", key, e.getMessage());
            return true;
        }
    }

    /**
     * 释放互斥锁（删除 Key；异常空转）。
     */
    public void unlock(String key) {
        delete(key);
    }

    /* ==================== 用户状态缓存（M10 修复：鉴权拦截器每请求查库的优化） ==================== */

    /** 用户状态缓存 Key 前缀 */
    private static final String USER_STATUS_KEY_PREFIX = "cache:user:status:";

    /** 用户状态缓存 TTL（秒）：短 TTL 防状态失真；管理员禁用账号时主动删缓存，保证禁用即时生效 */
    private static final long USER_STATUS_TTL_SECONDS = 60;

    /**
     * 缓存用户状态（存整数文本；异常空转）。
     */
    public void saveUserStatus(Long userId, Integer status) {
        if (!isEnabled() || userId == null || status == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(USER_STATUS_KEY_PREFIX + userId, String.valueOf(status),
                    USER_STATUS_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 写入用户状态缓存失败（userId={}）：{}", userId, e.getMessage());
        }
    }

    /**
     * 读取用户状态缓存：命中返回状态值；未命中 / 未启用 / 异常返回 null（调用方回源查库，即降级）。
     */
    public Integer getUserStatus(Long userId) {
        if (!isEnabled() || userId == null) {
            return null;
        }
        try {
            String v = redisTemplate.opsForValue().get(USER_STATUS_KEY_PREFIX + userId);
            if (v == null || v.isBlank()) {
                return null;
            }
            return Integer.valueOf(v);
        } catch (Exception e) {
            log.warn("Redis 读取用户状态缓存失败（userId={}）：{}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 删除用户状态缓存（用户状态变更/账号删除时主动失效，保证禁用即时生效；异常空转）。
     */
    public void removeUserStatus(Long userId) {
        if (userId == null) {
            return;
        }
        delete(USER_STATUS_KEY_PREFIX + userId);
    }

    /** 组装登录会话 Key：auth:token:{userId} */
    private String tokenKey(Long userId) {
        return redisProperties.getToken().getKeyPrefix() + userId;
    }

    /* ==================== 登录失败计数与锁定（N1 修复：登录状态迁 Redis，键 TTL 天然淘汰） ==================== */

    /** 登录失败计数 Key 前缀（完整 Key = 前缀 + 账号:角色；计数 TTL=锁定窗口，到期自动清除） */
    public static final String LOGIN_FAIL_KEY_PREFIX = "auth:login:fail:";
    /** 登录锁定 Key 前缀（完整 Key = 前缀 + 账号:角色；锁定键 TTL=锁定窗口，到期自动解锁） */
    public static final String LOGIN_LOCK_KEY_PREFIX = "auth:login:lock:";

    /**
     * 登录失败计数 +1（INCR + 每次刷新 EXPIRE，TTL=锁定窗口；到期 Key 自动清除，天然淘汰无内存增长）。
     * 异常不阻断登录（fail-open）：返回 0，由调用方按「未命中」处理。
     *
     * @param key        账号:角色（业务侧拼接，本方法补前缀）
     * @param ttlSeconds 计数 TTL（锁定窗口分钟数换算秒）
     * @return 最新失败计数（≥1）；未启用/异常返回 0
     */
    public long incrLoginFail(String key, long ttlSeconds) {
        if (!isEnabled() || StrUtil.isBlank(key)) {
            return 0;
        }
        try {
            String fullKey = LOGIN_FAIL_KEY_PREFIX + key;
            Long count = redisTemplate.opsForValue().increment(fullKey);
            redisTemplate.expire(fullKey, ttlSeconds, TimeUnit.SECONDS);
            return count == null ? 0 : count;
        } catch (Exception e) {
            // 写失败不阻断登录：本次失败按内存计数语义仍由调用方判定（降级）
            log.warn("Redis 写入登录失败计数失败（key={}）：{}", key, e.getMessage());
            return 0;
        }
    }

    /**
     * 写入账号锁定标记（SET + TTL=锁定窗口；到期自动解锁；异常空转，不阻断登录）。
     */
    public void lockLogin(String key, long ttlSeconds) {
        if (!isEnabled() || StrUtil.isBlank(key)) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(LOGIN_LOCK_KEY_PREFIX + key, "1", ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 写入登录锁定失败（key={}）：{}", key, e.getMessage());
        }
    }

    /**
     * 账号是否处于锁定（锁定键存在即锁定）。
     *
     * @return true=锁定中；false=未锁定 / 未启用 / 异常（异常按未锁定处理，不阻断登录）
     */
    public boolean isLoginLocked(String key) {
        if (!isEnabled() || StrUtil.isBlank(key)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(LOGIN_LOCK_KEY_PREFIX + key));
        } catch (Exception e) {
            // Redis 不可用：按未锁定放行，避免 Redis 故障阻断登录（降级）
            log.warn("Redis 读取登录锁定失败（key={}）：{}", key, e.getMessage());
            return false;
        }
    }

    /**
     * 锁定剩余秒数（提示剩余时间用；未锁定 / 未启用 / 异常返回 0）。
     */
    public long getLoginLockRemainSeconds(String key) {
        if (!isEnabled() || StrUtil.isBlank(key)) {
            return 0;
        }
        try {
            Long ttl = redisTemplate.getExpire(LOGIN_LOCK_KEY_PREFIX + key, TimeUnit.SECONDS);
            return ttl == null || ttl < 0 ? 0 : ttl;
        } catch (Exception e) {
            log.warn("Redis 读取登录锁定剩余时间失败（key={}）：{}", key, e.getMessage());
            return 0;
        }
    }

    /**
     * 清除该账号的失败计数与锁定（登录成功 / 改密 / 重置 = 解锁；未启用或异常空转）。
     */
    public void clearLoginTrack(String key) {
        if (StrUtil.isBlank(key)) {
            return;
        }
        delete(LOGIN_FAIL_KEY_PREFIX + key);
        delete(LOGIN_LOCK_KEY_PREFIX + key);
    }
}
