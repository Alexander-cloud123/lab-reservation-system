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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ClassroomMapper classroomMapper;

    @Resource
    private SysUserMapper userMapper;

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
    public Long createReservation(ReservationDTO dto) {
        // 必填校验
        if (dto.getClassroomId() == null) {
            throw new BusinessException("教室不能为空");
        }
        Classroom room = classroomMapper.selectById(dto.getClassroomId());
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
        if (StrUtil.isBlank(dto.getPurpose())) {
            throw new BusinessException("预约用途不能为空");
        }
        LocalDate reserveDate = TimeUtil.parseDate(dto.getReserveDate());
        LocalTime start = TimeUtil.parseTime(dto.getStartTime());
        LocalTime end = TimeUtil.parseTime(dto.getEndTime());
        if (!start.isBefore(end)) {
            throw new BusinessException("开始时间必须早于结束时间");
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
        reservation.setPurpose(dto.getPurpose().trim());
        reservation.setStatus(Constants.RES_STATUS_PENDING);
        reservationMapper.insert(reservation);
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
        // 实体 → VO（批量补全教室展示字段）
        return PageResult.of(result, this::toReservationVO);
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
        Reservation update = new Reservation();
        update.setId(id);
        update.setStatus(Constants.RES_STATUS_CANCELED);
        reservationMapper.updateById(update);
    }

    @Override
    public PageResult<ReservationManageVO> pageManage(long page, long size, Integer status,
                                                      String startDate, String endDate, String keyword,
                                                      Long classroomId) {
        validatePage(page, size);
        validateFilters(status, startDate, endDate, classroomId);
        LambdaQueryWrapper<Reservation> wrapper = buildManageWrapper(status, startDate, endDate, keyword, classroomId);
        Page<Reservation> result = reservationMapper.selectPage(new Page<>(page, size), wrapper);
        // 实体 → 管理端 VO（批量补全用户 + 教室信息）
        return PageResult.of(result, this::toManageVO);
    }

    @Override
    public List<ReservationExportVO> listExport(Integer status, String startDate, String endDate,
                                                String keyword, Long classroomId) {
        validateFilters(status, startDate, endDate, classroomId);
        LambdaQueryWrapper<Reservation> wrapper = buildManageWrapper(status, startDate, endDate, keyword, classroomId);
        // 导出全部命中记录（不分页），按创建时间倒序与 manage 列表口径一致
        List<Reservation> list = reservationMapper.selectList(wrapper);
        return list.stream().map(this::toExportVO).collect(Collectors.toList());
    }

    @Override
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

        Reservation update = new Reservation();
        update.setId(id);
        update.setStatus(dto.getStatus());
        // 通过不写备注；驳回写入管理员备注
        update.setAuditRemark(dto.getStatus() == Constants.RES_STATUS_REJECTED ? dto.getAuditRemark().trim() : null);
        update.setAuditorId(UserContext.getUserId());
        update.setAuditTime(LocalDateTime.now());
        reservationMapper.updateById(update);
    }

    @Override
    public int batchAudit(BatchAuditDTO dto) {
        if (dto.getIds() == null || dto.getIds().isEmpty()) {
            throw new BusinessException("预约 ID 列表不能为空");
        }
        validateAuditStatus(dto.getStatus());
        if (dto.getStatus() == Constants.RES_STATUS_REJECTED && StrUtil.isBlank(dto.getAuditRemark())) {
            throw new BusinessException("驳回必须填写审核备注");
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
        return reservationMapper.update(null, wrapper);
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

        return list.stream().map(r -> {
            CalendarVO vo = new CalendarVO();
            vo.setId(r.getId());
            vo.setClassroomId(r.getClassroomId());
            vo.setReserveDate(r.getReserveDate());
            vo.setStartTime(TimeUtil.formatTime(r.getStartTime()));
            vo.setEndTime(TimeUtil.formatTime(r.getEndTime()));
            vo.setPurpose(r.getPurpose());
            vo.setStatus(r.getStatus());
            vo.setAuditRemark(r.getAuditRemark());
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

    /** 预约状态合法性校验 */
    private boolean isValidResStatus(Integer status) {
        return status == Constants.RES_STATUS_PENDING
                || status == Constants.RES_STATUS_APPROVED
                || status == Constants.RES_STATUS_REJECTED
                || status == Constants.RES_STATUS_CANCELED;
    }

    /** 审核状态校验：1-通过，2-驳回 */
    private void validateAuditStatus(Integer status) {
        if (status == null || (status != Constants.RES_STATUS_APPROVED && status != Constants.RES_STATUS_REJECTED)) {
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

    /** 预约实体 → 学生端 VO（批量补全教室展示字段） */
    private ReservationVO toReservationVO(Reservation r) {
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
        // 教室展示字段（按需批量查询一次）
        Classroom room = classroomMapper.selectById(r.getClassroomId());
        if (room != null) {
            vo.setClassroomName(room.getName());
            vo.setBuilding(room.getBuilding());
            vo.setRoomNo(room.getRoomNo());
        }
        return vo;
    }

    /** 预约实体 → 管理端 VO（补全用户 + 教室信息） */
    private ReservationManageVO toManageVO(Reservation r) {
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
        SysUser user = userMapper.selectById(r.getUserId());
        if (user != null) {
            vo.setUserAccount(user.getUsername());
            vo.setUserName(user.getName());
        }
        Classroom room = classroomMapper.selectById(r.getClassroomId());
        if (room != null) {
            vo.setClassroomName(room.getName());
            vo.setBuilding(room.getBuilding());
            vo.setRoomNo(room.getRoomNo());
        }
        return vo;
    }

    /** 预约实体 → 导出 VO（补全用户 + 教室信息；时间统一字符串输出） */
    private ReservationExportVO toExportVO(Reservation r) {
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
        SysUser user = userMapper.selectById(r.getUserId());
        if (user != null) {
            vo.setUserAccount(user.getUsername());
            vo.setUserName(user.getName());
        }
        Classroom room = classroomMapper.selectById(r.getClassroomId());
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
