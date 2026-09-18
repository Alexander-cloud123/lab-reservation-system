package com.example.reservation.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.JwtUtil;
import com.example.reservation.common.PageResult;
import com.example.reservation.common.ResultCode;
import com.example.reservation.common.UserContext;
import com.example.reservation.config.RedisCache;
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
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 用户业务实现
 *
 * @author reservation-team
 */
@Service
public class UserServiceImpl implements UserService {

    /**
     * 登录失败计数（N1：内存降级 Map，仅 Redis 未启用时使用；key=账号:角色 → 连续失败次数；
     * 实例字段而非 static：@Service 单例下等价，且便于纯 Mockito 单测隔离）
     */
    private final Map<String, Integer> LOGIN_FAIL_COUNT = new ConcurrentHashMap<>();
    /** 账号锁定截止时间戳（N1：内存降级 Map，仅 Redis 未启用时使用；key=账号:角色 → 解锁时刻） */
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

    @Override
    public LoginVO login(LoginDTO dto) {
        // 参数校验
        if (StrUtil.hasBlank(dto.getUsername(), dto.getPassword()) || dto.getRole() == null) {
            throw new BusinessException("账号、密码、角色不能为空");
        }
        if (dto.getRole() != Constants.ROLE_STUDENT && dto.getRole() != Constants.ROLE_ADMIN) {
            throw new BusinessException("角色参数不合法");
        }

        // N1 修复：账号锁定检查（锁定状态优先落 Redis：auth:login:lock:{账号}:{角色}，键存在即锁定；
        // Redis 未启用时降级内存 Map 解锁时刻判断；锁定策略不变：连续 5 次失败锁 10 分钟）
        String failKey = dto.getUsername() + ":" + dto.getRole();
        if (isLoginLocked(failKey)) {
            long remainMinutes = Math.max(1, (getLoginLockRemainSeconds(failKey) + 59) / 60);
            throw new BusinessException("登录失败次数过多，账号已临时锁定，请约 " + remainMinutes + " 分钟后再试");
        }

        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        // 账号不存在与密码错误统一提示，避免账号枚举
        if (user == null || !BCrypt.checkpw(dto.getPassword(), user.getPassword())) {
            // N1：失败计数 +1（Redis INCR+TTL 自动淘汰 / 内存 merge），达到阈值进入锁定窗口（锁定期间直接拦截）
            long fails = recordLoginFail(failKey);
            if (fails >= Constants.LOGIN_FAIL_MAX_TIMES) {
                lockAccount(failKey);
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

        // 登录成功：清除失败计数与锁定状态（N1：Redis/内存双路径统一清理）
        clearLoginTrack(failKey);

        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        // Redis 加分项：登录会话写入 Redis（Key=auth:token:{userId}，TTL 与 JWT 一致）；
        // 同一用户重复登录覆盖旧 Token，实现「单点会话」（旧 Token 立即失效）；Redis 异常时封装层自动降级
        redisCache.saveToken(user.getId(), token, jwtUtil.getExpireSeconds());
        return new LoginVO(token, UserVO.from(user));
    }

    @Override
    public void logout() {
        // 删除 Redis 会话，登出后该 Token 立即失效（拦截器查不到会话即返回 401）；
        // 未登录场景 UserContext 无用户，直接返回；Redis 未启用/异常由封装层降级为空操作
        Long userId = UserContext.getUserId();
        if (userId != null) {
            redisCache.removeToken(userId);
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
        // 分页参数合法性校验（防恶意传参）
        if (page < 1) {
            throw new BusinessException("页码必须大于等于 1");
        }
        if (size < 1 || size > 500) {
            throw new BusinessException("每页条数必须在 1-500 之间");
        }
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
        // M10 配合：用户状态变更（禁用/启用）后主动失效状态缓存，保证下次请求即时生效（不等 60s TTL）
        redisCache.removeUserStatus(id);
    }

    @Override
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
        // M2 修复：重置密码后立即失效该用户 Redis 会话（"改密即下线"），旧 Token 不再可用；
        // N1：与 login 口径一致清理失败计数与锁定（管理员重置即人工解锁；Redis/内存双路径）
        redisCache.removeToken(id);
        clearLoginTrack(user.getUsername() + ":" + user.getRole());
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
        // M2 修复：修改密码后立即失效本人 Redis 会话（"改密即下线"），
        // 旧 Token 在改密瞬间即不可用（此前可继续使用最长 24 小时）
        redisCache.removeToken(user.getId());
        // N1 补齐：改密成功后与 resetPassword 一致清理失败计数与锁定（改密即解锁，Redis/内存双路径）
        clearLoginTrack(user.getUsername() + ":" + user.getRole());
    }

    /* ==================== 登录失败计数与锁定（N1 修复：Redis 优先 + 内存降级带容量上限） ==================== */

    /**
     * 账号是否处于锁定（Redis 优先：锁定键存在即锁定；Redis 未启用时读内存 Map 解锁时刻）。
     */
    private boolean isLoginLocked(String key) {
        if (redisCache.isEnabled()) {
            return redisCache.isLoginLocked(key);
        }
        Long lockUntil = LOGIN_LOCK_UNTIL.get(key);
        return lockUntil != null && System.currentTimeMillis() < lockUntil;
    }

    /**
     * 锁定剩余秒数（提示剩余时间；未锁定返回 0；Redis 未启用时按内存解锁时刻换算）。
     */
    private long getLoginLockRemainSeconds(String key) {
        if (redisCache.isEnabled()) {
            return redisCache.getLoginLockRemainSeconds(key);
        }
        Long lockUntil = LOGIN_LOCK_UNTIL.get(key);
        if (lockUntil == null) {
            return 0;
        }
        return Math.max(0, (lockUntil - System.currentTimeMillis() + 999) / 1000);
    }

    /**
     * 失败计数 +1，返回最新值（Redis INCR+TTL 天然淘汰；内存 merge + 容量定向清理）。
     */
    private long recordLoginFail(String key) {
        if (redisCache.isEnabled()) {
            return redisCache.incrLoginFail(key, Constants.LOGIN_LOCK_MINUTES * 60L);
        }
        // 内存降级路径：先写入再检查容量，超限定向清理（禁止 clear() 全清，避免所有账号计数一起归零）
        LOGIN_TRACK_ORDER.offer(key);
        int fails = LOGIN_FAIL_COUNT.merge(key, 1, Integer::sum);
        trimLoginTrack();
        return fails;
    }

    /**
     * 写入锁定状态（Redis 锁键 TTL=锁定窗口；内存记录解锁时刻并清计数）。
     */
    private void lockAccount(String key) {
        if (redisCache.isEnabled()) {
            redisCache.lockLogin(key, Constants.LOGIN_LOCK_MINUTES * 60L);
            return;
        }
        LOGIN_LOCK_UNTIL.put(key, System.currentTimeMillis() + Constants.LOGIN_LOCK_MINUTES * 60_000L);
        LOGIN_FAIL_COUNT.remove(key);
        LOGIN_TRACK_ORDER.offer(key);
    }

    /**
     * 清除该账号失败计数与锁定（登录成功 / 改密 / 重置 = 解锁；Redis/内存双路径）。
     */
    private void clearLoginTrack(String key) {
        if (redisCache.isEnabled()) {
            redisCache.clearLoginTrack(key);
            return;
        }
        LOGIN_FAIL_COUNT.remove(key);
        LOGIN_LOCK_UNTIL.remove(key);
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
