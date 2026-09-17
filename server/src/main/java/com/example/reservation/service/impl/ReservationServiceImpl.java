package com.example.reservation.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.PageResult;
import com.example.reservation.common.TimeUtil;
import com.example.reservation.common.UserContext;
import com.example.reservation.config.RedisCache;
import com.example.reservation.dto.AuditDTO;
import com.example.reservation.dto.BatchAuditDTO;
import com.example.reservation.dto.ReservationDTO;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.service.ReservationService;
import com.example.reservation.vo.CalendarVO;
import com.example.reservation.vo.ConflictVO;
import com.example.reservation.vo.ReservationExportVO;
import com.example.reservation.vo.ReservationManageVO;
import com.example.reservation.vo.ReservationVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 预约业务实现（R3 预约核心）
 * 核心规则（禁止简化）：
 *  1. 冲突检测公式「新开始 < 旧结束 AND 新结束 > 旧开始」，仅与【已通过(1)】预约比较；
 *  2. 状态流转：待审核(0) → 已通过(1)/已驳回(2)；待审核(0)/已通过(1) → 已取消(3)；
 *  3. 取消时限：预约开始前 1 小时内禁止取消；
 *  4. 审核/批量审核仅限待审核(0)记录，驳回必填审核备注。
 *
 * @author reservation-team
 */
@Service
public class ReservationServiceImpl implements ReservationService {

    /** 关键词无命中时的恒假条件 ID（表中不存在该 ID，保证查询恒空） */
    private static final long NO_MATCH_ID = -1L;

    /** 预约记录导出单次上限（超出提示缩小筛选范围，防全量导出拖垮内存/响应） */
    private static final int MAX_EXPORT_ROWS = 10000;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ClassroomMapper classroomMapper;

    @Resource
    private SysUserMapper userMapper;

    @Resource
    private RedisCache redisCache;

    @Override
    public ConflictVO checkConflict(Long classroomId, String date, String startTime, String endTime) {
        // 参数校验：教室存在性 + 日期/时间格式
        if (classroomId == null) {
            throw new BusinessException("教室 ID 不能为空");
        }
        if (classroomMapper.selectById(classroomId) == null) {
            throw new BusinessException("教室不存在");
        }
        if (StrUtil.isBlank(date)) {
            throw new BusinessException("预约日期不能为空");
        }
        if (StrUtil.isBlank(startTime) || StrUtil.isBlank(endTime)) {
            throw new BusinessException("预约时间不能为空");
        }
        LocalDate reserveDate = TimeUtil.parseDate(date);
        LocalTime start = TimeUtil.parseTime(startTime);
        LocalTime end = TimeUtil.parseTime(endTime);
        if (!start.isBefore(end)) {
            throw new BusinessException("开始时间必须早于结束时间");
        }

        // 查询该教室该日期【已通过】预约，按重叠公式判定
        List<Reservation> approved = listApprovedByClassAndDate(classroomId, reserveDate);
        for (Reservation r : approved) {
            // 冲突判定公式（需求文档 1.4）：新开始 < 旧结束 AND 新结束 > 旧开始
            if (start.isBefore(r.getEndTime()) && end.isAfter(r.getStartTime())) {
                String reason = "该时段与「" + TimeUtil.formatTime(r.getStartTime())
                        + "-" + TimeUtil.formatTime(r.getEndTime()) + "」的已通过预约冲突";
                return new ConflictVO(true, reason);
            }
        }
        return new ConflictVO(false, "该时段可预约");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createReservation(ReservationDTO dto) {
        // 必填校验
        if (dto.getClassroomId() == null) {
            throw new BusinessException("教室不能为空");
        }
        // 对教室行加锁（SELECT ... FOR UPDATE）：同一教室的并发预约提交串行化，保证"冲突检测+插入"原子，杜绝双写
        Classroom room = classroomMapper.selectOne(
                new LambdaQueryWrapper<Classroom>().eq(Classroom::getId, dto.getClassroomId()).last("FOR UPDATE"));
        if (room == null) {
            throw new BusinessException("教室不存在");
        }
        if (room.getStatus() != Constants.CLASSROOM_STATUS_ENABLED) {
            throw new BusinessException("该教室已停用，无法预约");
        }
        if (StrUtil.isBlank(dto.getReserveDate())) {
            throw new BusinessException("预约日期不能为空");
        }
        if (StrUtil.isBlank(dto.getStartTime()) || StrUtil.isBlank(dto.getEndTime())) {
            throw new BusinessException("预约时间不能为空");
        }
        // R4 修复：用途去空格后做空值 + 长度校验（与 reservation.purpose VARCHAR(255) 对齐，
        // 防直调接口传超长文本触发数据库 Data too long → 500）
        String purpose = dto.getPurpose() == null ? "" : dto.getPurpose().trim();
        if (purpose.isBlank()) {
            throw new BusinessException("预约用途不能为空");
        }
        if (purpose.length() > Constants.PURPOSE_MAX_LENGTH) {
            throw new BusinessException("预约用途过长（不超过 " + Constants.PURPOSE_MAX_LENGTH + " 字）");
        }
        LocalDate reserveDate = TimeUtil.parseDate(dto.getReserveDate());
        LocalTime start = TimeUtil.parseTime(dto.getStartTime());
        LocalTime end = TimeUtil.parseTime(dto.getEndTime());
        if (!start.isBefore(end)) {
            throw new BusinessException("开始时间必须早于结束时间");
        }
        // H3 修复：可预约时段窗口后端强制（需求文档 1.4「每日 08:00-22:00 可预约」此前仅在统计分母体现，提交侧零校验；
        // 现在作为后端兜底强制，前端绕过直接调用本接口同样被拒绝，杜绝"凌晨占用教室"与使用率超 100%）
        if (start.isBefore(Constants.DAILY_SLOT_START) || end.isAfter(Constants.DAILY_SLOT_END)) {
            throw new BusinessException("可预约时段为每日 " + TimeUtil.formatTime(Constants.DAILY_SLOT_START)
                    + "-" + TimeUtil.formatTime(Constants.DAILY_SLOT_END));
        }
        // H3 修复：单次预约时长上限（防止单条记录占满全天，答辩口径：防恶意占满资源）
        if (ChronoUnit.MINUTES.between(start, end) > Constants.MAX_RESERVATION_HOURS * 60L) {
            throw new BusinessException("单次预约时长不能超过 " + Constants.MAX_RESERVATION_HOURS + " 小时");
        }
        // 禁止预约过去日期（与前端 disabled-date 口径一致：允许今天及以后，后端强制兜底）
        if (reserveDate.isBefore(LocalDate.now())) {
            throw new BusinessException("预约日期不能早于今天");
        }
        // M12 修复：预约日期为今天时，开始时刻必须晚于当前时刻（此前仅拦截过去日期、未拦今天已过去的时刻）
        if (reserveDate.isEqual(LocalDate.now()) && !start.isAfter(LocalTime.now())) {
            throw new BusinessException("预约开始时间必须晚于当前时间");
        }

        // 后端二次冲突检测（强制兜底：绕过前端直接调用本接口仍会被拒绝）
        ConflictVO conflict = checkConflict(dto.getClassroomId(), dto.getReserveDate(), dto.getStartTime(), dto.getEndTime());
        if (Boolean.TRUE.equals(conflict.getConflict())) {
            throw new BusinessException(conflict.getReason());
        }

        // 插入预约，初始状态：待审核(0)
        Reservation reservation = new Reservation();
        reservation.setUserId(UserContext.getUserId());
        reservation.setClassroomId(dto.getClassroomId());
        reservation.setReserveDate(reserveDate);
        reservation.setStartTime(start);
        reservation.setEndTime(end);
        reservation.setPurpose(purpose);
        reservation.setStatus(Constants.RES_STATUS_PENDING);
        reservationMapper.insert(reservation);
        // 缓存一致性：预约提交（月度趋势按全部状态统计，随之变化），失效看板 + 教室列表缓存
        redisCache.evictBusinessCaches();
        return reservation.getId();
    }

    @Override
    public PageResult<ReservationVO> pageMine(long page, long size, Integer status) {
        validatePage(page, size);
        // 状态筛选参数合法性校验
        if (status != null && !isValidResStatus(status)) {
            throw new BusinessException("状态参数不合法（0-待审核，1-已通过，2-已驳回，3-已取消）");
        }
        LambdaQueryWrapper<Reservation> wrapper = new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, UserContext.getUserId())
                .eq(status != null, Reservation::getStatus, status)
                .orderByDesc(Reservation::getCreateTime);
        Page<Reservation> result = reservationMapper.selectPage(new Page<>(page, size), wrapper);
        // 实体 → VO（批量预查教室信息，避免逐行 selectById 造成 N+1 查询）
        Map<Long, Classroom> roomMap = batchClassroomMap(result.getRecords());
        return PageResult.of(result, r -> toReservationVO(r, roomMap));
    }

    @Override
    public void cancelReservation(Long id) {
        if (id == null) {
            throw new BusinessException("预约 ID 不能为空");
        }
        Reservation reservation = reservationMapper.selectById(id);
        if (reservation == null) {
            throw new BusinessException("预约不存在");
        }
        // 只能取消自己的预约
        if (!reservation.getUserId().equals(UserContext.getUserId())) {
            throw new BusinessException("只能取消自己的预约");
        }
        // 状态流转：仅待审核(0)/已通过(1)可取消；已驳回/已取消不可再取消
        if (reservation.getStatus() != Constants.RES_STATUS_PENDING
                && reservation.getStatus() != Constants.RES_STATUS_APPROVED) {
            throw new BusinessException("当前状态不可取消");
        }
        // 取消时限：预约开始前 1 小时内（含已开始）禁止取消（需求文档 1.4）
        LocalDateTime start = LocalDateTime.of(reservation.getReserveDate(), reservation.getStartTime());
        if (LocalDateTime.now().isAfter(start.minusHours(Constants.RESERVATION_CANCEL_HOURS))) {
            throw new BusinessException("预约开始前 1 小时内禁止取消，如需调整请联系管理员");
        }

        // 待审核(0)/已通过(1) → 已取消(3)
        // M1 修复：条件更新 + 影响行数校验（UPDATE ... WHERE id=? AND status IN (0,1)），
        // 并发下学生取消与管理员审核互踩时，后写者不再覆盖前写（已被审核的记录不会被"取消"覆盖，已被取消的也不会被"复活"）
        LambdaUpdateWrapper<Reservation> cancelWrapper = new LambdaUpdateWrapper<Reservation>()
                .eq(Reservation::getId, id)
                .and(w -> w.eq(Reservation::getStatus, Constants.RES_STATUS_PENDING)
                        .or().eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED))
                .set(Reservation::getStatus, Constants.RES_STATUS_CANCELED);
        int updated = reservationMapper.update(null, cancelWrapper);
        if (updated != 1) {
            throw new BusinessException("预约状态已变更，请刷新后重试");
        }
        // 缓存一致性：取消预约影响看板与教室今日占用，失效相关缓存
        redisCache.evictBusinessCaches();
    }

    @Override
    public PageResult<ReservationManageVO> pageManage(long page, long size, Integer status,
                                                      String startDate, String endDate, String keyword,
                                                      Long classroomId) {
        validatePage(page, size);
        validateFilters(status, startDate, endDate, classroomId);
        LambdaQueryWrapper<Reservation> wrapper = buildManageWrapper(status, startDate, endDate, keyword, classroomId);
        Page<Reservation> result = reservationMapper.selectPage(new Page<>(page, size), wrapper);
        // 实体 → 管理端 VO（批量预查用户 + 教室信息，避免逐行 selectById 造成 N+1 查询）
        Map<Long, SysUser> userMap = batchUserMap(result.getRecords());
        Map<Long, Classroom> roomMap = batchClassroomMap(result.getRecords());
        return PageResult.of(result, r -> toManageVO(r, userMap, roomMap));
    }

    @Override
    public List<ReservationExportVO> listExport(Integer status, String startDate, String endDate,
                                                String keyword, Long classroomId) {
        validateFilters(status, startDate, endDate, classroomId);
        LambdaQueryWrapper<Reservation> wrapper = buildManageWrapper(status, startDate, endDate, keyword, classroomId);
        // 导出上限保护：最多导出 MAX_EXPORT_ROWS 条，超出提示缩小筛选范围（防全量导出拖垮内存/响应）
        Page<Reservation> page = reservationMapper.selectPage(new Page<>(1, MAX_EXPORT_ROWS + 1), wrapper);
        if (page.getTotal() > MAX_EXPORT_ROWS) {
            throw new BusinessException("导出数据超过 " + MAX_EXPORT_ROWS + " 条上限，请缩小筛选范围后导出");
        }
        List<Reservation> list = page.getRecords();
        // 批量预查用户 + 教室信息（避免逐行 selectById 造成 N+1 查询）
        Map<Long, SysUser> userMap = batchUserMap(list);
        Map<Long, Classroom> roomMap = batchClassroomMap(list);
        return list.stream().map(r -> toExportVO(r, userMap, roomMap)).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void auditReservation(Long id, AuditDTO dto) {
        if (id == null) {
            throw new BusinessException("预约 ID 不能为空");
        }
        validateAuditStatus(dto.getStatus());
        Reservation reservation = reservationMapper.selectById(id);
        if (reservation == null) {
            throw new BusinessException("预约不存在");
        }
        // 仅待审核(0)可审核
        if (reservation.getStatus() != Constants.RES_STATUS_PENDING) {
            throw new BusinessException("仅待审核状态的预约可审核");
        }
        // 驳回必填审核备注
        if (dto.getStatus() == Constants.RES_STATUS_REJECTED && StrUtil.isBlank(dto.getAuditRemark())) {
            throw new BusinessException("驳回必须填写审核备注");
        }
        // 通过前复查冲突（核心规则兜底）：防止多个重叠待审核先后被通过产生"双已通过"
        if (dto.getStatus() == Constants.RES_STATUS_APPROVED) {
            // 锁教室行串行化同教室审核：并发审核时后者重读已通过列表，杜绝复查冲突的 TOCTOU 竞态
            classroomMapper.selectOne(new LambdaQueryWrapper<Classroom>()
                    .eq(Classroom::getId, reservation.getClassroomId()).last("FOR UPDATE"));
            // H2 修复：复审列表改为【加锁读】（SELECT ... FOR UPDATE，读最新已提交），
            // 此前先普通 SELECT（selectById）建立旧快照、再加锁，FOR UPDATE 只提供互斥、复查仍读旧快照，
            // 并发事务刚提交的"已通过"不可见 → 改为加锁读后复查必然读到最新已提交结果，闭合快照顺序缺口
            List<Reservation> approved = listApprovedByClassAndDateForUpdate(
                    reservation.getClassroomId(), reservation.getReserveDate());
            for (Reservation r : approved) {
                // 重叠公式（需求文档 1.4）：新开始 < 旧结束 AND 新结束 > 旧开始
                if (reservation.getStartTime().isBefore(r.getEndTime()) && reservation.getEndTime().isAfter(r.getStartTime())) {
                    throw new BusinessException("审核失败：与已通过预约「" + TimeUtil.formatTime(r.getStartTime())
                            + "-" + TimeUtil.formatTime(r.getEndTime()) + "」时间冲突，不能通过");
                }
            }
        }

        // M1 修复：条件更新 + 影响行数校验（UPDATE ... SET status=? WHERE id=? AND status=0），
        // 防止并发下覆盖他人已完成的审核结果（如学生并发取消后仍被置为已通过）
        LambdaUpdateWrapper<Reservation> auditWrapper = new LambdaUpdateWrapper<Reservation>()
                .eq(Reservation::getId, id)
                .eq(Reservation::getStatus, Constants.RES_STATUS_PENDING)
                .set(Reservation::getStatus, dto.getStatus())
                // 通过不写备注；驳回写入管理员备注
                .set(Reservation::getAuditRemark,
                        dto.getStatus() == Constants.RES_STATUS_REJECTED ? dto.getAuditRemark().trim() : null)
                .set(Reservation::getAuditorId, UserContext.getUserId())
                .set(Reservation::getAuditTime, LocalDateTime.now());
        int updated = reservationMapper.update(null, auditWrapper);
        if (updated != 1) {
            throw new BusinessException("预约状态已变更，请刷新后重试");
        }
        // 缓存一致性：审核改变预约状态（已通过/已驳回），影响看板与教室今日占用，失效相关缓存
        redisCache.evictBusinessCaches();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchAudit(BatchAuditDTO dto) {
        if (dto.getIds() == null || dto.getIds().isEmpty()) {
            throw new BusinessException("预约 ID 列表不能为空");
        }
        validateAuditStatus(dto.getStatus());
        if (dto.getStatus() == Constants.RES_STATUS_REJECTED && StrUtil.isBlank(dto.getAuditRemark())) {
            throw new BusinessException("驳回必须填写审核备注");
        }
        // 批量通过前逐条复查冲突（核心规则兜底）：任一条与已通过预约冲突则整批拒绝，避免产生"双已通过"
        if (dto.getStatus() == Constants.RES_STATUS_APPROVED) {
            List<Reservation> targets = reservationMapper.selectBatchIds(dto.getIds());
            // 锁涉及教室行（按教室 ID 排序加锁，避免并发批量审核交叉死锁），串行化同教室审核，杜绝 TOCTOU 竞态
            targets.stream().map(Reservation::getClassroomId).filter(Objects::nonNull).distinct().sorted()
                    .forEach(cid -> classroomMapper.selectOne(
                            new LambdaQueryWrapper<Classroom>().eq(Classroom::getId, cid).last("FOR UPDATE")));
            List<Reservation> pendingTargets = targets.stream()
                    .filter(r -> r.getStatus() != null && r.getStatus() == Constants.RES_STATUS_PENDING)
                    .toList();
            // H1 修复：批内互斥校验——同批待通过记录按（教室, 日期）分组两两判定重叠。
            // 此前循环体只与库中 status=1 比对，同批 A、B 此时均为待审核(0)、互不在对方比对集合内，
            // 一次 UI 操作即可产生"同一教室同一时段两条已通过"；现对组内排序后线性两两判定，冲突整批拒绝并指名 ID
            Map<Long, Map<LocalDate, List<Reservation>>> byClassAndDate = pendingTargets.stream()
                    .collect(Collectors.groupingBy(Reservation::getClassroomId,
                            Collectors.groupingBy(Reservation::getReserveDate)));
            for (Map<LocalDate, List<Reservation>> dateGroups : byClassAndDate.values()) {
                for (List<Reservation> group : dateGroups.values()) {
                    if (group.size() < 2) {
                        continue;
                    }
                    List<Reservation> sorted = new ArrayList<>(group);
                    sorted.sort(Comparator.comparing(Reservation::getStartTime));
                    for (int i = 0; i < sorted.size() - 1; i++) {
                        Reservation a = sorted.get(i);
                        // 重叠公式（需求文档 1.4）：新开始 < 旧结束 AND 新结束 > 旧开始
                        for (int j = i + 1; j < sorted.size(); j++) {
                            Reservation b = sorted.get(j);
                            if (b.getStartTime().isBefore(a.getEndTime()) && b.getEndTime().isAfter(a.getStartTime())) {
                                throw new BusinessException("批量审核失败：预约 ID=" + b.getId()
                                        + " 与同批预约 ID=" + a.getId()
                                        + " 时段重叠（同一教室同一日期），请单独处理");
                            }
                        }
                    }
                }
            }
            for (Reservation r : pendingTargets) {
                // H2 修复：复审已通过列表改用【加锁读】（读最新已提交），闭合"快照读早于加锁"的并发缺口
                List<Reservation> approved = listApprovedByClassAndDateForUpdate(r.getClassroomId(), r.getReserveDate());
                for (Reservation ap : approved) {
                    // 重叠公式（需求文档 1.4）：新开始 < 旧结束 AND 新结束 > 旧开始
                    if (r.getStartTime().isBefore(ap.getEndTime()) && r.getEndTime().isAfter(ap.getStartTime())) {
                        throw new BusinessException("批量审核失败：预约 ID=" + r.getId()
                                + " 与已通过预约「" + TimeUtil.formatTime(ap.getStartTime())
                                + "-" + TimeUtil.formatTime(ap.getEndTime()) + "」时间冲突，请单独处理");
                    }
                }
            }
        }
        // 批量更新：仅【待审核(0)】记录可参与，返回实际更新条数
        LambdaUpdateWrapper<Reservation> wrapper = new LambdaUpdateWrapper<Reservation>()
                .in(Reservation::getId, dto.getIds())
                .eq(Reservation::getStatus, Constants.RES_STATUS_PENDING)
                .set(Reservation::getStatus, dto.getStatus())
                .set(Reservation::getAuditRemark,
                        dto.getStatus() == Constants.RES_STATUS_REJECTED ? dto.getAuditRemark().trim() : null)
                .set(Reservation::getAuditorId, UserContext.getUserId())
                .set(Reservation::getAuditTime, LocalDateTime.now());
        int updated = reservationMapper.update(null, wrapper);
        // 缓存一致性：批量审核改变预约状态，失效看板 + 教室列表缓存
        redisCache.evictBusinessCaches();
        return updated;
    }

    @Override
    public List<CalendarVO> listCalendar(Long classroomId, String startDate, String endDate) {
        // startDate/endDate 必填（日历需要明确区间，防止全表扫描）
        if (StrUtil.isBlank(startDate)) {
            throw new BusinessException(Constants.CALENDAR_START_REQUIRED_MSG);
        }
        if (StrUtil.isBlank(endDate)) {
            throw new BusinessException(Constants.CALENDAR_END_REQUIRED_MSG);
        }
        LocalDate start = TimeUtil.parseDate(startDate);
        LocalDate end = TimeUtil.parseDate(endDate);
        if (start.isAfter(end)) {
            throw new BusinessException("开始日期不能晚于结束日期");
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > Constants.CALENDAR_MAX_DAYS) {
            throw new BusinessException(Constants.CALENDAR_RANGE_MSG);
        }
        // 教室筛选：传 classroomId 则校验教室存在
        if (classroomId != null && classroomMapper.selectById(classroomId) == null) {
            throw new BusinessException("教室不存在");
        }

        // 区间内全部状态预约（色块覆盖四状态），按教室/日期/开始时间排序，前端按月/周聚合
        List<Reservation> list = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(classroomId != null, Reservation::getClassroomId, classroomId)
                .ge(Reservation::getReserveDate, start)
                .le(Reservation::getReserveDate, end)
                .orderByAsc(Reservation::getClassroomId)
                .orderByAsc(Reservation::getReserveDate)
                .orderByAsc(Reservation::getStartTime));

        // 批量补全教室展示字段（一次性查询，避免逐条 N+1）
        Map<Long, Classroom> roomMap = classroomMapper.selectList(new LambdaQueryWrapper<Classroom>())
                .stream().collect(Collectors.toMap(Classroom::getId, Function.identity()));

        // M8 修复 + R3 收敛：日历接口对任意登录用户开放（学生/管理员均可查看占用）；
        // 用途与审核备注仅【管理员或本人】可见，其余学生只见时段与状态（避免越权可见他人用途与驳回备注）
        boolean isAdmin = UserContext.isAdmin();

        return list.stream().map(r -> {
            CalendarVO vo = new CalendarVO();
            vo.setId(r.getId());
            vo.setClassroomId(r.getClassroomId());
            vo.setReserveDate(r.getReserveDate());
            vo.setStartTime(TimeUtil.formatTime(r.getStartTime()));
            vo.setEndTime(TimeUtil.formatTime(r.getEndTime()));
            // R3 修复：本人预约标记（前端据此决定是否显示用途）；仅管理员或本人可见完整信息
            boolean mine = UserContext.isSelf(r.getUserId());
            vo.setMine(mine);
            if (isAdmin || mine) {
                // 管理员可见完整信息（含用途与审核备注，供审核与溯源）；本人仅可见自己预约的完整信息
                vo.setPurpose(r.getPurpose());
                vo.setAuditRemark(r.getAuditRemark());
            }
            vo.setStatus(r.getStatus());
            Classroom room = roomMap.get(r.getClassroomId());
            if (room != null) {
                vo.setClassroomName(room.getName());
                vo.setBuilding(room.getBuilding());
                vo.setRoomNo(room.getRoomNo());
            }
            return vo;
        }).collect(Collectors.toList());
    }

    /* ==================== 私有工具方法 ==================== */

    /** 分页参数合法性校验（防恶意传参） */
    private void validatePage(long page, long size) {
        if (page < 1) {
            throw new BusinessException("页码必须大于等于 1");
        }
        if (size < 1 || size > 500) {
            throw new BusinessException("每页条数必须在 1-500 之间");
        }
    }

    /** 预约状态合法性校验（L10 修复：Integer 相等比较，null 直接返回 false，避免拆箱 NPE） */
    private boolean isValidResStatus(Integer status) {
        return Integer.valueOf(Constants.RES_STATUS_PENDING).equals(status)
                || Integer.valueOf(Constants.RES_STATUS_APPROVED).equals(status)
                || Integer.valueOf(Constants.RES_STATUS_REJECTED).equals(status)
                || Integer.valueOf(Constants.RES_STATUS_CANCELED).equals(status);
    }

    /** 审核状态校验：1-通过，2-驳回（L10 修复：Integer 相等比较，防拆箱 NPE） */
    private void validateAuditStatus(Integer status) {
        if (status == null || (!Integer.valueOf(Constants.RES_STATUS_APPROVED).equals(status)
                && !Integer.valueOf(Constants.RES_STATUS_REJECTED).equals(status))) {
            throw new BusinessException("审核状态参数不合法（1-通过，2-驳回）");
        }
    }

    /** 解析可选日期参数（空返回 null，非法抛 400） */
    private LocalDate parseDateOrNull(String date) {
        return StrUtil.isBlank(date) ? null : TimeUtil.parseDate(date);
    }

    /** 管理端查询/导出共用筛选参数校验（状态合法、日期先后、教室存在） */
    private void validateFilters(Integer status, String startDate, String endDate, Long classroomId) {
        if (status != null && !isValidResStatus(status)) {
            throw new BusinessException("状态参数不合法（0-待审核，1-已通过，2-已驳回，3-已取消）");
        }
        LocalDate start = parseDateOrNull(startDate);
        LocalDate end = parseDateOrNull(endDate);
        // 日期倒序属于参数错误：直接 400，避免静默返回空结果误导用户（R6 边界补全）
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException("开始日期不能晚于结束日期");
        }
        if (classroomId != null && classroomMapper.selectById(classroomId) == null) {
            throw new BusinessException("教室不存在");
        }
    }

    /** 构建管理端查询/导出共用筛选条件（状态/日期范围/教室/关键词），按创建时间倒序 */
    private LambdaQueryWrapper<Reservation> buildManageWrapper(Integer status, String startDate, String endDate,
                                                               String keyword, Long classroomId) {
        LocalDate start = parseDateOrNull(startDate);
        LocalDate end = parseDateOrNull(endDate);

        // 关键词（用户账号/姓名、教室名称/楼栋/编号）→ 用户 ID 集合、教室 ID 集合
        final Set<Long> userIds;
        final Set<Long> keywordClassroomIds;
        if (StrUtil.isNotBlank(keyword)) {
            List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getName, keyword));
            List<Classroom> rooms = classroomMapper.selectList(new LambdaQueryWrapper<Classroom>()
                    .like(Classroom::getName, keyword)
                    .or().like(Classroom::getBuilding, keyword)
                    .or().like(Classroom::getRoomNo, keyword));
            userIds = users.stream().map(SysUser::getId).collect(Collectors.toSet());
            keywordClassroomIds = rooms.stream().map(Classroom::getId).collect(Collectors.toSet());
            if (userIds.isEmpty() && keywordClassroomIds.isEmpty()) {
                // 关键词无任何命中 → 恒假条件（id=-1 不存在），分页与导出统一返回空结果
                return new LambdaQueryWrapper<Reservation>()
                        .eq(Reservation::getId, NO_MATCH_ID)
                        .orderByDesc(Reservation::getCreateTime);
            }
        } else {
            userIds = null;
            keywordClassroomIds = null;
        }

        return new LambdaQueryWrapper<Reservation>()
                .eq(status != null, Reservation::getStatus, status)
                // R6：预约记录页「教室」筛选（可选参数，缺省全部教室）
                .eq(classroomId != null, Reservation::getClassroomId, classroomId)
                .ge(start != null, Reservation::getReserveDate, start)
                .le(end != null, Reservation::getReserveDate, end)
                // 关键词条件：避免空集合 IN () 导致 SQL 语法错误，按命中情况分别拼接
                .and(StrUtil.isNotBlank(keyword), w -> {
                    if (userIds.isEmpty()) {
                        w.in(Reservation::getClassroomId, keywordClassroomIds);
                    } else if (keywordClassroomIds.isEmpty()) {
                        w.in(Reservation::getUserId, userIds);
                    } else {
                        w.in(Reservation::getUserId, userIds).or().in(Reservation::getClassroomId, keywordClassroomIds);
                    }
                })
                .orderByDesc(Reservation::getCreateTime);
    }

    /** 查询某教室某日期【已通过】预约（冲突检测/时段占用共用） */
    private List<Reservation> listApprovedByClassAndDate(Long classroomId, LocalDate date) {
        return reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getClassroomId, classroomId)
                .eq(Reservation::getReserveDate, date)
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED)
                .orderByAsc(Reservation::getStartTime));
    }

    /**
     * 查询某教室某日期【已通过】预约（加锁读，审核复查专用）
     * H2 修复：审核路径先普通 SELECT 已建立旧快照，再 FOR UPDATE 只提供互斥；此处用 SELECT ... FOR UPDATE
     * 做"当前读"（读最新已提交），保证复查能看到并发事务刚提交的"已通过"结果，闭合快照顺序缺口。
     * 不影响 checkConflict 等实时校验语义（那里不需要加锁，快照读更轻量）。
     */
    private List<Reservation> listApprovedByClassAndDateForUpdate(Long classroomId, LocalDate date) {
        return reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getClassroomId, classroomId)
                .eq(Reservation::getReserveDate, date)
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED)
                .orderByAsc(Reservation::getStartTime)
                .last("FOR UPDATE"));
    }

    /** 批量预查教室信息 → id→Classroom Map（空集合安全，避免逐行 selectById 造成 N+1） */
    private Map<Long, Classroom> batchClassroomMap(List<Reservation> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> ids = list.stream().map(Reservation::getClassroomId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return classroomMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Classroom::getId, Function.identity(), (a, b) -> a));
    }

    /** 批量预查用户信息 → id→SysUser Map（空集合安全） */
    private Map<Long, SysUser> batchUserMap(List<Reservation> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> ids = list.stream().map(Reservation::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity(), (a, b) -> a));
    }

    /** 预约实体 → 学生端 VO（教室展示字段来自批量预查 Map，避免 N+1） */
    private ReservationVO toReservationVO(Reservation r, Map<Long, Classroom> roomMap) {
        ReservationVO vo = new ReservationVO();
        vo.setId(r.getId());
        vo.setClassroomId(r.getClassroomId());
        vo.setReserveDate(r.getReserveDate());
        vo.setStartTime(TimeUtil.formatTime(r.getStartTime()));
        vo.setEndTime(TimeUtil.formatTime(r.getEndTime()));
        vo.setPurpose(r.getPurpose());
        vo.setStatus(r.getStatus());
        vo.setAuditRemark(r.getAuditRemark());
        vo.setCreateTime(r.getCreateTime());
        Classroom room = roomMap.get(r.getClassroomId());
        if (room != null) {
            vo.setClassroomName(room.getName());
            vo.setBuilding(room.getBuilding());
            vo.setRoomNo(room.getRoomNo());
        }
        return vo;
    }

    /** 预约实体 → 管理端 VO（补全用户 + 教室信息，来自批量预查 Map） */
    private ReservationManageVO toManageVO(Reservation r, Map<Long, SysUser> userMap, Map<Long, Classroom> roomMap) {
        ReservationManageVO vo = new ReservationManageVO();
        vo.setId(r.getId());
        vo.setUserId(r.getUserId());
        vo.setClassroomId(r.getClassroomId());
        vo.setReserveDate(r.getReserveDate());
        vo.setStartTime(TimeUtil.formatTime(r.getStartTime()));
        vo.setEndTime(TimeUtil.formatTime(r.getEndTime()));
        vo.setPurpose(r.getPurpose());
        vo.setStatus(r.getStatus());
        vo.setAuditRemark(r.getAuditRemark());
        vo.setAuditorId(r.getAuditorId());
        vo.setAuditTime(r.getAuditTime());
        vo.setCreateTime(r.getCreateTime());
        SysUser user = userMap.get(r.getUserId());
        if (user != null) {
            vo.setUserAccount(user.getUsername());
            vo.setUserName(user.getName());
        }
        Classroom room = roomMap.get(r.getClassroomId());
        if (room != null) {
            vo.setClassroomName(room.getName());
            vo.setBuilding(room.getBuilding());
            vo.setRoomNo(room.getRoomNo());
        }
        return vo;
    }

    /** 预约实体 → 导出 VO（补全用户 + 教室信息；时间统一字符串输出） */
    private ReservationExportVO toExportVO(Reservation r, Map<Long, SysUser> userMap, Map<Long, Classroom> roomMap) {
        ReservationExportVO vo = new ReservationExportVO();
        vo.setId(r.getId());
        vo.setReserveDate(String.valueOf(r.getReserveDate()));
        vo.setStartTime(TimeUtil.formatTime(r.getStartTime()));
        vo.setEndTime(TimeUtil.formatTime(r.getEndTime()));
        vo.setPurpose(r.getPurpose());
        vo.setStatusText(statusText(r.getStatus()));
        vo.setAuditRemark(r.getAuditRemark());
        vo.setAuditTime(TimeUtil.formatDateTime(r.getAuditTime()));
        vo.setCreateTime(TimeUtil.formatDateTime(r.getCreateTime()));
        SysUser user = userMap.get(r.getUserId());
        if (user != null) {
            vo.setUserAccount(user.getUsername());
            vo.setUserName(user.getName());
        }
        Classroom room = roomMap.get(r.getClassroomId());
        if (room != null) {
            vo.setClassroomName(room.getName());
            vo.setBuilding(room.getBuilding());
            vo.setRoomNo(room.getRoomNo());
        }
        return vo;
    }

    /** 预约状态 → 导出展示文案（与前端状态标签口径一致：0-待审核，1-已通过，2-已驳回，3-已取消） */
    private String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case Constants.RES_STATUS_PENDING -> "待审核";
            case Constants.RES_STATUS_APPROVED -> "已通过";
            case Constants.RES_STATUS_REJECTED -> "已驳回";
            case Constants.RES_STATUS_CANCELED -> "已取消";
            default -> "未知";
        };
    }
}
