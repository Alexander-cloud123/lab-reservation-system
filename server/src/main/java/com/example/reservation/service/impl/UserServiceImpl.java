package com.example.reservation.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.JwtUtil;
import com.example.reservation.common.PageResult;
import com.example.reservation.common.PageValidator;
import com.example.reservation.common.ResultCode;
import com.example.reservation.common.UserContext;
import com.example.reservation.config.RedisCache;
import com.example.reservation.config.TransactionCacheInvalidator;
import com.example.reservation.dto.LoginDTO;
import com.example.reservation.dto.PasswordDTO;
import com.example.reservation.dto.RegisterDTO;
import com.example.reservation.dto.UserInfoDTO;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.service.UserService;
import com.example.reservation.vo.LoginVO;
import com.example.reservation.vo.UserStatsVO;
import com.example.reservation.vo.UserVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 用户业务实现
 *
 * @author hyr
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {

    /**
     * 登录失败计数（内存降级 Map；key=规范化登录账号 → 连续失败次数；
     * 实例字段而非 static：@Service 单例下等价，且便于纯 Mockito 单测隔离）
     */
    private final Map<String, Integer> LOGIN_FAIL_COUNT = new ConcurrentHashMap<>();
    /** 账号锁定截止时间戳（内存降级 Map；key=规范化登录账号 → 解锁时刻；Redis 写锁失败时同样使用） */
    private final Map<String, Long> LOGIN_LOCK_UNTIL = new ConcurrentHashMap<>();
    /** 内存降级路径写入顺序队列（N1：配合容量上限定向清理，删最旧写入条目；禁止 clear() 全清） */
    private final Queue<String> LOGIN_TRACK_ORDER = new ConcurrentLinkedQueue<>();

    @Resource
    private SysUserMapper userMapper;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private RedisCache redisCache;

    /** AI 开关读取（只读 ai_config，不侵入业务规则；仅用于登录时向前端下发 AI 开关） */
    @Resource
    private AiConfigService aiConfigService;

    @Override
    public LoginVO login(LoginDTO dto) {
        // 参数校验
        if (StrUtil.hasBlank(dto.getUsername(), dto.getPassword()) || dto.getRole() == null) {
            throw new BusinessException("账号、密码、角色不能为空");
        }
        if (dto.getRole() != Constants.ROLE_STUDENT && dto.getRole() != Constants.ROLE_ADMIN) {
            throw new BusinessException("角色参数不合法");
        }

        // 登录查询与防爆破桶使用同一规范化账号，匹配 MySQL utf8mb4_general_ci 的大小写/尾随空格语义；
        // 桶只包含账号，不包含客户端提交的 role，避免 ADMIN/Admin/admin 空格及角色变体换桶绕过。
        String loginName = normalizeLoginUsername(dto.getUsername());
        // 锁定策略不变：连续 5 次失败锁 10 分钟；Redis 未启用或不可用时均读内存降级锁。
        if (isLoginLocked(loginName)) {
            long remainMinutes = Math.max(1, (getLoginLockRemainSeconds(loginName) + 59) / 60);
            throw new BusinessException("登录失败次数过多，账号已临时锁定，请约 " + remainMinutes + " 分钟后再试");
        }

        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, loginName));
        // 账号不存在与密码错误统一提示，避免账号枚举
        if (user == null || !BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            // 失败计数 +1（Redis INCR+TTL 自动淘汰 / 内存 merge），达到阈值进入锁定窗口（锁定期间直接拦截）
            long fails = recordLoginFail(loginName);
            if (fails >= Constants.LOGIN_FAIL_MAX_TIMES) {
                lockAccount(loginName);
            }
            throw new BusinessException("账号或密码错误");
        }
        // 状态校验
        if (user.getStatus() == Constants.USER_STATUS_DISABLED) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "账号已被禁用，请联系管理员");
        }
        // 角色匹配校验（学生账号不能以管理员身份登录）
        if (user.getRole() != dto.getRole()) {
            throw new BusinessException("角色选择与账号类型不匹配");
        }

        // 登录成功：清除失败计数与锁定状态（Redis/内存双路径统一清理）
        clearLoginTrack(loginName);

        // Token 携带当前版本号：此后任何撤销动作（登出/改密/重置/禁用）自增版本，旧 Token 立即失效。
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getTokenVersion());
        // Redis 启用：同一用户重复登录覆盖旧 Token，实现单点会话；会话读写异常时后续校验 fail closed。
        // Redis 禁用：没有单点会话，但撤销仍由 token_version 在服务端持久生效。
        redisCache.saveToken(user.getId(), token, jwtUtil.getExpireSeconds());
        // AI 开关随登录下发：前端据此直接决定 AI 入口显隐，省掉一次 /ai/recommend 探测调用
        return new LoginVO(token, UserVO.from(user), aiConfigService.isAiEnabled());
    }

    @Override
    public void logout() {
        // 服务端持久撤销：数据库 token_version 自增（权威），Redis 会话与状态缓存提交后清理。
        // Redis 关闭或删除失败都不影响撤销结果——旧 Token 因版本不匹配必然 401。
        Long userId = UserContext.getUserId();
        if (userId != null) {
            revokeSession(userId);
        }
    }

    @Override
    public void register(RegisterDTO dto) {
        // 必填项校验
        if (StrUtil.hasBlank(dto.getUsername(), dto.getPassword(), dto.getConfirmPassword(), dto.getName(), dto.getStudentNo())) {
            throw new BusinessException("请完整填写必填信息（账号/密码/确认密码/姓名/学号）");
        }
        if (dto.getUsername().length() > 32) {
            throw new BusinessException("账号长度不能超过 32 个字符");
        }
        if (dto.getName().length() > 20) {
            throw new BusinessException("姓名长度不能超过 20 个字符");
        }
        if (dto.getStudentNo().length() > 20) {
            throw new BusinessException("学号长度不能超过 20 个字符");
        }
        if (StrUtil.isNotBlank(dto.getEmail()) && dto.getEmail().length() > 50) {
            throw new BusinessException("邮箱长度不能超过 50 个字符");
        }
        if (dto.getPassword().length() < Constants.PASSWORD_MIN_LENGTH) {
            throw new BusinessException("密码长度不能少于 6 位");
        }
        // 两次密码一致性校验
        if (!dto.getPassword().equals(dto.getConfirmPassword())) {
            throw new BusinessException("两次输入的密码不一致");
        }
        // 手机号格式校验（选填，填写则必须为 11 位手机号）
        if (StrUtil.isNotBlank(dto.getPhone()) && !dto.getPhone().matches("1\\d{10}")) {
            throw new BusinessException("手机号格式不正确");
        }
        // 账号唯一性校验
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException("该账号已被注册，请更换账号");
        }

        // 注册角色固定为学生，默认正常状态，密码 BCrypt 加密存储（数据库中无明文）
        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(BCrypt.hashpw(dto.getPassword()));
        user.setName(dto.getName());
        user.setStudentNo(dto.getStudentNo());
        user.setPhone(dto.getPhone());
        user.setEmail(dto.getEmail());
        user.setRole(Constants.ROLE_STUDENT);
        user.setStatus(Constants.USER_STATUS_NORMAL);
        userMapper.insert(user);
    }

    @Override
    public UserVO getCurrentUser() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage());
        }
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户不存在或已被删除");
        }
        return UserVO.from(user);
    }

    @Override
    public PageResult<UserVO> pageUsers(long page, long size, String keyword, Integer role, Integer status) {
        // 分页参数合法性校验（防恶意传参，公共校验器）
        PageValidator.validate(page, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                // 关键词：账号 / 姓名 / 学号 模糊匹配
                .and(StrUtil.isNotBlank(keyword), w -> w
                        .like(SysUser::getUsername, keyword)
                        .or().like(SysUser::getName, keyword)
                        .or().like(SysUser::getStudentNo, keyword))
                // 角色 / 状态筛选
                .eq(role != null, SysUser::getRole, role)
                .eq(status != null, SysUser::getStatus, status)
                // 统一排序：创建时间倒序（spec.md 5.1）
                .orderByDesc(SysUser::getCreateTime);
        Page<SysUser> result = userMapper.selectPage(new Page<>(page, size), wrapper);
        // 实体 → VO 转换，剔除 password 字段，保证列表接口不返回密码
        return PageResult.of(result, UserVO::from);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserStatus(Long id, Integer status) {
        if (id == null) {
            throw new BusinessException("用户 ID 不能为空");
        }
        if (status == null || (status != Constants.USER_STATUS_DISABLED && status != Constants.USER_STATUS_NORMAL)) {
            throw new BusinessException("状态参数不合法（0-禁用，1-正常）");
        }
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        // 防护：管理员不允许禁用/启用当前登录的自己（R2 规则）
        if (id.equals(UserContext.getUserId())) {
            throw new BusinessException("不允许操作当前登录的管理员账号");
        }
        // 目标状态与当前状态一致时直接返回（幂等）
        if (user.getStatus() != null && user.getStatus().equals(status)) {
            return;
        }
        SysUser update = new SysUser();
        update.setId(id);
        update.setStatus(status);
        userMapper.updateById(update);
        // 状态变更即撤销该用户已签发 Token（禁用→旧 Token 401；重新启用后也需重新登录），
        // 并在提交后清理状态缓存，保证下一次请求即时生效（不等 60s TTL）
        revokeSession(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id) {
        if (id == null) {
            throw new BusinessException("用户 ID 不能为空");
        }
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        // 防护：管理员不允许重置当前登录的自己（R2 规则）
        if (id.equals(UserContext.getUserId())) {
            throw new BusinessException("不允许操作当前登录的管理员账号");
        }
        // 重置为默认密码，BCrypt 加密存储（数据库中无明文）
        SysUser update = new SysUser();
        update.setId(id);
        update.setPassword(BCrypt.hashpw(Constants.DEFAULT_PASSWORD));
        userMapper.updateById(update);
        // 重置密码后撤销该用户已签发 Token（token_version 自增，与密码更新同事务，任一步失败整体回滚）；
        // 同时按登录规范化口径清理失败计数与锁定（管理员重置即人工解锁；不引入角色维度）。
        revokeSession(id);
        clearLoginTrack(normalizeLoginUsername(user.getUsername()));
    }

    @Override
    public UserStatsVO getStats() {
        Long userId = UserContext.getUserId();
        // 累计预约次数：本人全部状态预约记录数
        Long total = reservationMapper.selectCount(
                new LambdaQueryWrapper<Reservation>().eq(Reservation::getUserId, userId));
        // 本月预约数：预约日期属于当前自然月
        YearMonth month = YearMonth.now();
        LocalDate firstDay = month.atDay(1);
        LocalDate lastDay = month.atEndOfMonth();
        Long monthCount = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .ge(Reservation::getReserveDate, firstDay)
                .le(Reservation::getReserveDate, lastDay));
        // 审核通过率 = 已通过 /（已通过 + 已驳回）× 100，保留一位小数；无审核记录为 0
        Long approved = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED));
        Long rejected = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getStatus, Constants.RES_STATUS_REJECTED));
        Double rate = (approved + rejected) == 0
                ? 0.0
                : Math.round(approved * 1000.0 / (approved + rejected)) / 10.0;
        // 最近一次预约时间：按创建时间取最新一条（无记录为 null）
        Reservation last = reservationMapper.selectOne(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .orderByDesc(Reservation::getCreateTime)
                .last("LIMIT 1"));

        UserStatsVO vo = new UserStatsVO();
        vo.setTotalReservations(total);
        vo.setMonthReservations(monthCount);
        vo.setApprovalRate(rate);
        vo.setLastReservationTime(last == null ? null : last.getCreateTime());
        return vo;
    }

    @Override
    public void updateInfo(UserInfoDTO dto) {
        // 姓名必填且长度受限（表字段 varchar(20)）
        if (StrUtil.isBlank(dto.getName())) {
            throw new BusinessException("姓名不能为空");
        }
        if (dto.getName().length() > 20) {
            throw new BusinessException("姓名长度不能超过 20 个字符");
        }
        // 手机号格式校验（选填，填写则必须为 11 位手机号，与注册口径一致）
        if (StrUtil.isNotBlank(dto.getPhone()) && !dto.getPhone().matches("1\\d{10}")) {
            throw new BusinessException("手机号格式不正确");
        }
        // 学号 / 邮箱长度受限（表字段 varchar(20) / varchar(50)）
        if (StrUtil.isNotBlank(dto.getStudentNo()) && dto.getStudentNo().length() > 20) {
            throw new BusinessException("学号长度不能超过 20 个字符");
        }
        if (StrUtil.isNotBlank(dto.getEmail()) && dto.getEmail().length() > 50) {
            throw new BusinessException("邮箱长度不能超过 50 个字符");
        }
        // 仅更新姓名/学号/邮箱/手机号；DTO 不含账号/密码/角色/状态字段，
        // 配合 MyBatis-Plus 默认 NOT_NULL 更新策略，其余字段不会被改动
        SysUser update = new SysUser();
        update.setId(UserContext.getUserId());
        update.setName(dto.getName().trim());
        update.setStudentNo(dto.getStudentNo());
        update.setPhone(dto.getPhone());
        update.setEmail(dto.getEmail());
        userMapper.updateById(update);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePassword(PasswordDTO dto) {
        // 必填校验
        if (StrUtil.hasBlank(dto.getOldPassword(), dto.getNewPassword(), dto.getConfirmPassword())) {
            throw new BusinessException("原密码、新密码、确认密码不能为空");
        }
        if (dto.getNewPassword().length() < Constants.PASSWORD_MIN_LENGTH) {
            throw new BusinessException("新密码长度不能少于 6 位");
        }
        // 两次新密码一致性校验
        if (!dto.getNewPassword().equals(dto.getConfirmPassword())) {
            throw new BusinessException("两次输入的新密码不一致");
        }
        SysUser user = userMapper.selectById(UserContext.getUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "用户不存在或已被删除");
        }
        // 验证原密码（BCrypt 比对）
        if (!BCrypt.checkpw(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException("原密码错误");
        }
        // 新密码 BCrypt 加密存储（数据库中无明文）
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(BCrypt.hashpw(dto.getNewPassword()));
        userMapper.updateById(update);
        // 改密后撤销本人已签发 Token（token_version 自增，与密码更新同事务）；提交后清理会话与状态缓存。
        // 旧 Token 无论 Redis 是否可用、实例是否重启都立即失效，无需再向上报告撤销不确定性。
        revokeSession(user.getId());
        clearLoginTrack(normalizeLoginUsername(user.getUsername()));
    }

    /**
     * 撤销该用户全部已签发 Token：sys_user.token_version 原子自增（撤销凭据在数据库，跨实例/重启有效），
     * 并在事务提交后清理 Redis 会话与鉴权状态缓存（提交前清理会与回滚/并发回填竞态）。
     */
    private void revokeSession(Long userId) {
        if (userId == null) {
            return;
        }
        if (userMapper.bumpTokenVersion(userId) == 0) {
            // 用户已被删除：没有可撤销的 Token，仅记录日志（调用方此前已做过存在性校验）
            log.warn("撤销会话时用户不存在，跳过版本自增（userId={}）", userId);
            return;
        }
        TransactionCacheInvalidator.afterCommit(() -> redisCache.invalidateSession(userId));
    }

    /** 登录专用账号规范化；注册唯一性与其它业务查询仍保留原输入，不扩大改动范围。 */
    private String normalizeLoginUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /* ==================== 登录失败计数与锁定（Redis 优先 + 内存降级带容量上限） ==================== */

    /**
     * 账号是否处于锁定。先读本地锁，保证 Redis 写锁失败后的降级锁在故障及恢复切换期间都真实有效；
     * 本地未锁定时再读共享 Redis 锁，Redis 读异常仍可由本地锁兜底。
     */
    private boolean isLoginLocked(String key) {
        if (isMemoryLoginLocked(key)) {
            return true;
        }
        return redisCache.isEnabled() && redisCache.isLoginLocked(key);
    }

    /** 锁定剩余秒数；两种锁并存时取较长窗口，Redis 读异常不影响本地锁的剩余时间。 */
    private long getLoginLockRemainSeconds(String key) {
        long memoryRemain = getMemoryLoginLockRemainSeconds(key);
        if (redisCache.isEnabled()) {
            return Math.max(memoryRemain, redisCache.getLoginLockRemainSeconds(key));
        }
        return memoryRemain;
    }

    /** 读取本地锁并惰性清理已过期条目，模拟 Redis TTL 到期自动删除。 */
    private boolean isMemoryLoginLocked(String key) {
        return getMemoryLoginLockRemainSeconds(key) > 0;
    }

    /** 本地锁剩余秒数（向上取整）；到期时仅删除当前 key，避免过期数据无限滞留。 */
    private long getMemoryLoginLockRemainSeconds(String key) {
        Long lockUntil = LOGIN_LOCK_UNTIL.get(key);
        if (lockUntil == null) {
            return 0;
        }
        long remainMillis = lockUntil - System.currentTimeMillis();
        if (remainMillis <= 0) {
            LOGIN_LOCK_UNTIL.remove(key, lockUntil);
            return 0;
        }
        return (remainMillis + 999) / 1000;
    }

    /**
     * 失败计数 +1，返回最新值（Redis INCR+TTL 天然淘汰；内存 merge + 容量定向清理）。
     * 以返回值而非开关判定计数路径：Redis 启用但写入异常（incr 返回 0）时退回内存计数；
     * 故障窗口内退化，恢复后以 Redis 计数为准，但已写入的本地锁仍会执行到自身 TTL 到期。
     */
    private long recordLoginFail(String key) {
        if (redisCache.isEnabled()) {
            long fails = redisCache.incrLoginFail(key, Constants.LOGIN_LOCK_MINUTES * 60L);
            if (fails > 0) {
                return fails;                       // Redis 正常：以 Redis 计数为准
            }
            log.warn("Redis 登录失败计数不可用，退化内存计数（key={}）", key);
            // 落到下方内存路径（故障窗口内退化，恢复后以 Redis 为准）
        }
        // 内存降级路径：先写入再检查容量，超限定向清理（禁止 clear() 全清，避免所有账号计数一起归零）
        LOGIN_TRACK_ORDER.offer(key);
        capTrackOrder();
        int fails = LOGIN_FAIL_COUNT.merge(key, 1, Integer::sum);
        trimLoginTrack();
        return fails;
    }

    /**
     * 写入锁定状态。Redis 写锁返回失败时必须落到同一账号的本地 TTL 锁，不能只写计数后放行；
     * 达到阈值后清除内存失败计数，避免故障恢复后旧计数导致一次失败立即再次锁定。
     */
    private void lockAccount(String key) {
        long lockSeconds = Constants.LOGIN_LOCK_MINUTES * 60L;
        boolean redisLocked = redisCache.isEnabled() && redisCache.lockLogin(key, lockSeconds);
        if (!redisLocked) {
            if (redisCache.isEnabled()) {
                log.warn("Redis 登录锁定不可用，写入内存 TTL 锁（key={}）", key);
            }
            LOGIN_LOCK_UNTIL.put(key, System.currentTimeMillis() + lockSeconds * 1000L);
            LOGIN_TRACK_ORDER.offer(key);
            capTrackOrder();
        }
        LOGIN_FAIL_COUNT.remove(key);
        trimLoginTrack();
    }

    /** 清除该账号失败计数与锁定（登录成功 / 改密 / 重置 = 解锁）；本地状态始终清理，不能遗留降级锁。 */
    private void clearLoginTrack(String key) {
        if (redisCache.isEnabled()) {
            redisCache.clearLoginTrack(key);
        }
        LOGIN_FAIL_COUNT.remove(key);
        LOGIN_LOCK_UNTIL.remove(key);
    }

    /**
     * 顺序队列长度硬上限（N1 残留 A 修复）：队列是「写入顺序」的近似载体，允许去重缺失，
     * 但长度必须有界——否则 Redis 关闭/故障的降级路径上，轮换用户名刷失败请求仍能让队列无界增长
     * （两个 Map 有 trimLoginTrack 兜底 ≤ LOGIN_TRACK_MAX_ACCOUNTS，队列此前没有任何上限）。
     * 在每次 offer 之后调用；与 trimLoginTrack 的「Map 容量定向清理」职责互不替代。
     */
    private void capTrackOrder() {
        while (LOGIN_TRACK_ORDER.size() > Constants.LOGIN_TRACK_MAX_ACCOUNTS * 2) {
            LOGIN_TRACK_ORDER.poll();
        }
    }

    /**
     * 内存降级路径容量控制：超上限时定向清理——先删已过期的锁定条目，仍超再按写入顺序删最旧条目。
     * 注意：这是尽力而为的定向清理（防无限增长），非强一致淘汰。
     */
    private void trimLoginTrack() {
        if (LOGIN_FAIL_COUNT.size() + LOGIN_LOCK_UNTIL.size() <= Constants.LOGIN_TRACK_MAX_ACCOUNTS) {
            return;
        }
        // 1) 先删已过期的锁定条目（模拟 TTL 语义：解锁时刻已到即释放）
        LOGIN_LOCK_UNTIL.entrySet().removeIf(e -> System.currentTimeMillis() >= e.getValue());
        if (LOGIN_FAIL_COUNT.size() + LOGIN_LOCK_UNTIL.size() <= Constants.LOGIN_TRACK_MAX_ACCOUNTS) {
            return;
        }
        // 2) 仍超上限：按写入顺序删最旧条目（队列可能含已被删除的 key，轮询直到删掉一个真实存在的条目）
        while (!LOGIN_TRACK_ORDER.isEmpty()) {
            String oldest = LOGIN_TRACK_ORDER.poll();
            if (oldest != null
                    && (LOGIN_FAIL_COUNT.remove(oldest) != null || LOGIN_LOCK_UNTIL.remove(oldest) != null)) {
                break;
            }
        }
    }
}
