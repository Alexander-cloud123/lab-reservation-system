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
import com.example.reservation.config.RedisProperties;
import com.example.reservation.dto.ClassroomDTO;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.UserFavorite;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.UserFavoriteMapper;
import com.example.reservation.service.ClassroomService;
import com.example.reservation.vo.ClassroomVO;
import com.example.reservation.vo.OccupiedSlotVO;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 教室业务实现
 * 删除保护规则（R2）：该教室存在任何预约记录时禁止删除，返回 400 提示
 *
 * @author reservation-team
 */
@Service
public class ClassroomServiceImpl implements ClassroomService {

    /** 今日可预约时段口径（与前端日历/详情页一致）：08:00-22:00，按整点划分为 14 个时段 */
    private static final int DAILY_SLOT_START_HOUR = 8;
    private static final int DAILY_SLOT_END_HOUR = 22;

    /** 教室列表缓存 Key 版本号（R5：旧版缓存条目仍含他人 purpose，升版本号使旧 Key 部署即失效、重建为裁剪后数据） */
    private static final String LIST_CACHE_VERSION = "v2:";

    @Resource
    private ClassroomMapper classroomMapper;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private UserFavoriteMapper favoriteMapper;

    @Resource
    private RedisCache redisCache;

    @Resource
    private RedisProperties redisProperties;

    @Override
    public PageResult<Classroom> pageClassrooms(long page, long size, String keyword, String building, Integer type, Integer status) {
        // 分页参数合法性校验
        if (page < 1) {
            throw new BusinessException("页码必须大于等于 1");
        }
        if (size < 1 || size > 500) {
            throw new BusinessException("每页条数必须在 1-500 之间");
        }
        LambdaQueryWrapper<Classroom> wrapper = new LambdaQueryWrapper<Classroom>()
                // 关键词：名称 / 编号 模糊匹配
                .and(StrUtil.isNotBlank(keyword), w -> w
                        .like(Classroom::getName, keyword)
                        .or().like(Classroom::getRoomNo, keyword))
                // 楼栋 / 类型 / 状态筛选
                .eq(StrUtil.isNotBlank(building), Classroom::getBuilding, building)
                .eq(type != null, Classroom::getType, type)
                .eq(status != null, Classroom::getStatus, status)
                // 统一排序：创建时间倒序（spec.md 5.1）
                .orderByDesc(Classroom::getCreateTime);
        Page<Classroom> result = classroomMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result);
    }

    @Override
    public Long createClassroom(ClassroomDTO dto) {
        validateClassroomDTO(dto);
        Classroom classroom = new Classroom();
        classroom.setName(dto.getName());
        classroom.setBuilding(dto.getBuilding());
        classroom.setRoomNo(dto.getRoomNo());
        classroom.setType(dto.getType());
        classroom.setCapacity(dto.getCapacity());
        classroom.setEquipment(dto.getEquipment());
        classroom.setDescription(dto.getDescription());
        // 新增默认可用状态
        classroom.setStatus(Constants.CLASSROOM_STATUS_ENABLED);
        classroomMapper.insert(classroom);
        // 缓存一致性：教室基础数据变更，失效看板 + 教室列表缓存（下次读回源重建）
        redisCache.evictBusinessCaches();
        return classroom.getId();
    }

    @Override
    public void updateClassroom(ClassroomDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("教室 ID 不能为空");
        }
        Classroom exists = classroomMapper.selectById(dto.getId());
        if (exists == null) {
            throw new BusinessException("教室不存在");
        }
        validateClassroomDTO(dto);
        Classroom classroom = new Classroom();
        classroom.setId(dto.getId());
        classroom.setName(dto.getName());
        classroom.setBuilding(dto.getBuilding());
        classroom.setRoomNo(dto.getRoomNo());
        classroom.setType(dto.getType());
        classroom.setCapacity(dto.getCapacity());
        classroom.setEquipment(dto.getEquipment());
        classroom.setDescription(dto.getDescription());
        classroomMapper.updateById(classroom);
        // 缓存一致性：教室基础数据变更，失效看板 + 教室列表缓存
        redisCache.evictBusinessCaches();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteClassroom(Long id) {
        if (id == null) {
            throw new BusinessException("教室 ID 不能为空");
        }
        // R2 修复：存在性校验改为加锁读（SELECT ... FOR UPDATE），与 createReservation:120 争抢同一把教室行锁，
        // 删除与并发预约提交互斥——删除先拿锁 → 预约阻塞且删后查不到教室；预约先拿锁 → 删除阻塞，
        // 待其提交后计数必 > 0 → 正确拒绝。仅 @Transactional 只提供原子性、不提供互斥。
        // 此句同时成为本事务首条语句（加锁读不建 read view），后续 selectCount 读到最新已提交数据，
        // 避免"计数 0 → 并发插入 → 删除成功"的悬挂引用。
        Classroom exists = classroomMapper.selectOne(new LambdaQueryWrapper<Classroom>()
                .eq(Classroom::getId, id).last("FOR UPDATE"));
        if (exists == null) {
            throw new BusinessException("教室不存在");
        }
        // 删除保护：该教室存在任何预约记录（不限状态）时禁止删除
        Long reservationCount = reservationMapper.selectCount(
                new LambdaQueryWrapper<Reservation>().eq(Reservation::getClassroomId, id));
        if (reservationCount > 0) {
            throw new BusinessException("该教室存在预约记录，禁止删除");
        }
        // H4 修复：删除教室时一并清理该教室的收藏记录（user_favorite 无外键、无级联删除），
        // 否则学生个人中心出现字段为空的"幽灵收藏卡"（无法取消、永久占用收藏配额 10 间）。
        // M5 修复：删除保护检查与删除动作置于同一事务，避免检查与删除之间并发提交的预约悬挂 classroomId
        favoriteMapper.delete(new LambdaQueryWrapper<UserFavorite>().eq(UserFavorite::getClassroomId, id));
        classroomMapper.deleteById(id);
        // 缓存一致性：教室删除，失效看板 + 教室列表缓存
        redisCache.evictBusinessCaches();
    }

    @Override
    public void updateClassroomStatus(Long id, Integer status) {
        if (id == null) {
            throw new BusinessException("教室 ID 不能为空");
        }
        if (status == null || (status != Constants.CLASSROOM_STATUS_DISABLED && status != Constants.CLASSROOM_STATUS_ENABLED)) {
            throw new BusinessException("状态参数不合法（0-停用，1-可用）");
        }
        Classroom exists = classroomMapper.selectById(id);
        if (exists == null) {
            throw new BusinessException("教室不存在");
        }
        // 目标状态与当前状态一致时直接返回（幂等）
        if (exists.getStatus() != null && exists.getStatus().equals(status)) {
            return;
        }
        Classroom update = new Classroom();
        update.setId(id);
        update.setStatus(status);
        classroomMapper.updateById(update);
        // 缓存一致性：教室启停影响学生端可见性与看板，失效相关缓存
        redisCache.evictBusinessCaches();
    }

    @Override
    public int batchUpdateStatus(List<Long> ids, Integer status) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("教室 ID 列表不能为空");
        }
        if (status == null || (status != Constants.CLASSROOM_STATUS_DISABLED && status != Constants.CLASSROOM_STATUS_ENABLED)) {
            throw new BusinessException("状态参数不合法（0-停用，1-可用）");
        }
        // 批量更新（条件：id in ids）
        LambdaUpdateWrapper<Classroom> wrapper = new LambdaUpdateWrapper<Classroom>()
                .in(Classroom::getId, ids)
                .set(Classroom::getStatus, status);
        int updated = classroomMapper.update(null, wrapper);
        // 缓存一致性：批量启停影响学生端可见性与看板，失效相关缓存
        redisCache.evictBusinessCaches();
        return updated;
    }

    @Override
    public PageResult<ClassroomVO> pageClassroomsForStudent(long page, long size, String keyword,
                                                            String building, Integer type, String date) {
        // 分页参数合法性校验
        if (page < 1) {
            throw new BusinessException("页码必须大于等于 1");
        }
        if (size < 1 || size > 500) {
            throw new BusinessException("每页条数必须在 1-500 之间");
        }
        // 可选日期参数（传了才解析，用于返回该日期的占用时段；非法格式 400）
        LocalDate queryDate = StrUtil.isBlank(date) ? null : TimeUtil.parseDate(date);

        // Redis 加分项：教室列表为高频读，按查询参数指纹做短缓存（含今日剩余时段等动态数据，仅短 TTL，不做长缓存）；
        // Redis 异常时 getObject 返回 null，自动回源查库，接口行为与不启用缓存完全一致
        String cacheKey = classroomListKey(page, size, keyword, building, type, date);
        PageResult<ClassroomVO> cached = redisCache.getObject(cacheKey, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }

        LambdaQueryWrapper<Classroom> wrapper = new LambdaQueryWrapper<Classroom>()
                // 关键词：名称 / 编号 模糊匹配
                .and(StrUtil.isNotBlank(keyword), w -> w
                        .like(Classroom::getName, keyword)
                        .or().like(Classroom::getRoomNo, keyword))
                // 楼栋 / 类型筛选
                .eq(StrUtil.isNotBlank(building), Classroom::getBuilding, building)
                .eq(type != null, Classroom::getType, type)
                // 学生端仅展示可用教室（口径标注：停用教室学生不可见、不可预约）
                .eq(Classroom::getStatus, Constants.CLASSROOM_STATUS_ENABLED)
                // 统一排序：创建时间倒序（spec.md 5.1）
                .orderByDesc(Classroom::getCreateTime);
        Page<Classroom> result = classroomMapper.selectPage(new Page<>(page, size), wrapper);
        List<Long> ids = result.getRecords().stream().map(Classroom::getId).toList();

        // 当天已通过预约（实时状态标签计算用，一次查询避免逐教室 N+1）
        List<Reservation> todayApproved = ids.isEmpty() ? List.of()
                : reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                        .in(Reservation::getClassroomId, ids)
                        .eq(Reservation::getReserveDate, LocalDate.now())
                        .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED));
        // 指定日期占用时段（仅当传入 date 时查询返回）
        final Map<Long, List<OccupiedSlotVO>> occupiedMap;
        if (queryDate != null && !ids.isEmpty()) {
            List<Reservation> dayApproved = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                    .in(Reservation::getClassroomId, ids)
                    .eq(Reservation::getReserveDate, queryDate)
                    .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED)
                    .orderByAsc(Reservation::getStartTime));
            occupiedMap = dayApproved.stream()
                    .collect(Collectors.groupingBy(Reservation::getClassroomId,
                            // R5 修复：列表路径（共享缓存）只带时段，不返回用途与 mine——
                            // 缓存 Key 不含用户维度，存"依赖请求者身份"的字段会把首个请求者固化进缓存、泄露给他人
                            Collectors.mapping(this::toSlotVOForList, Collectors.toList())));
        } else {
            occupiedMap = Map.of();
        }

        List<ClassroomVO> vos = result.getRecords().stream().map(c -> {
            ClassroomVO vo = toStudentVO(c);
            vo.setStatusLabel(calcStatusLabel(c.getId(), todayApproved));
            vo.setTodayRemainingSlots(calcRemainingSlots(c.getId(), todayApproved));
            vo.setOccupiedSlots(occupiedMap.getOrDefault(c.getId(), List.of()));
            return vo;
        }).toList();
        PageResult<ClassroomVO> pageResult = new PageResult<>(result.getTotal(), vos);
        // 回填短缓存（教室增改/停用、预约提交/审核/取消时主动失效，保证核心数据一致性）
        redisCache.setObject(cacheKey, pageResult, redisProperties.getCache().getClassroomTtlSeconds());
        return pageResult;
    }

    @Override
    public ClassroomVO getClassroomDetail(Long id, String date) {
        if (id == null) {
            throw new BusinessException("教室 ID 不能为空");
        }
        Classroom classroom = classroomMapper.selectById(id);
        if (classroom == null) {
            throw new BusinessException("教室不存在");
        }
        // 指定日期：不传默认当天
        LocalDate queryDate = StrUtil.isBlank(date) ? LocalDate.now() : TimeUtil.parseDate(date);

        // 当天已通过预约（实时状态标签用）
        List<Reservation> todayApproved = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getClassroomId, id)
                .eq(Reservation::getReserveDate, LocalDate.now())
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED));
        // 指定日期（默认当天）已通过预约时段占用列表
        List<Reservation> dayApproved = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getClassroomId, id)
                .eq(Reservation::getReserveDate, queryDate)
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED)
                .orderByAsc(Reservation::getStartTime));

        ClassroomVO vo = toStudentVO(classroom);
        vo.setStatusLabel(calcStatusLabel(id, todayApproved));
        // R5 修复：详情路径（无缓存）按身份裁剪——仅管理员或本人可见用途，其余学生时段仍可查看但用途为 null
        boolean isAdmin = UserContext.isAdmin();
        vo.setOccupiedSlots(dayApproved.stream().map(r -> toSlotVO(r, isAdmin)).toList());
        return vo;
    }

    /**
     * 教室实体 → 学生端 VO（基础展示字段，状态标签与占用时段由调用方填充）
     */
    private ClassroomVO toStudentVO(Classroom c) {
        ClassroomVO vo = new ClassroomVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setBuilding(c.getBuilding());
        vo.setRoomNo(c.getRoomNo());
        vo.setType(c.getType());
        vo.setCapacity(c.getCapacity());
        vo.setEquipment(c.getEquipment());
        vo.setDescription(c.getDescription());
        vo.setStatus(c.getStatus());
        return vo;
    }

    /**
     * 实时状态标签口径（需求文档 1.3 冲优项，R3 口径 + R4 补全已结束态）：
     * 以当天为基准——
     *  1. 存在已通过预约且当前时刻 ∈ [开始, 结束) → 使用中（红）；
     *  2. 当天存在已通过预约且当前时刻 ≥ 全部时段结束时间 → 已结束（灰）；
     *  3. 其余（当天无已通过预约，或预约尚未开始）→ 当前空闲（绿）。
     */
    private String calcStatusLabel(Long classroomId, List<Reservation> todayApproved) {
        List<Reservation> mine = todayApproved.stream()
                .filter(r -> r.getClassroomId().equals(classroomId))
                .toList();
        if (mine.isEmpty()) {
            return Constants.STATUS_LABEL_FREE;
        }
        LocalTime now = LocalTime.now();
        // 使用中：当前时刻落在任一已通过时段内（开始含、结束不含）
        boolean inUse = mine.stream()
                .anyMatch(r -> !now.isBefore(r.getStartTime()) && now.isBefore(r.getEndTime()));
        if (inUse) {
            return Constants.STATUS_LABEL_IN_USE;
        }
        // 已结束：当天所有已通过时段均已结束（当前时刻 ≥ 每个时段结束时间）
        boolean allEnded = mine.stream().allMatch(r -> !now.isBefore(r.getEndTime()));
        return allEnded ? Constants.STATUS_LABEL_ENDED : Constants.STATUS_LABEL_FREE;
    }

    /**
     * 今日剩余可预约整点时段数（需求文档 1.3 冲优项「今日剩余 X 时段」，负责人 2026-09-12 授权）：
     * 08:00-22:00 按整点划分为 14 个时段；某时段与任一今日已通过预约重叠
     * （R1 冲突检测公式：时段开始 < 预约结束 AND 时段结束 > 预约开始）即视为不可约；
     * 剩余 = 14 - 不可约时段数。仅与「今天」绑定，与列表页日期筛选参数无关。
     */
    private int calcRemainingSlots(Long classroomId, List<Reservation> todayApproved) {
        List<Reservation> mine = todayApproved.stream()
                .filter(r -> r.getClassroomId().equals(classroomId))
                .toList();
        if (mine.isEmpty()) {
            return DAILY_SLOT_END_HOUR - DAILY_SLOT_START_HOUR;
        }
        Set<Integer> occupied = new HashSet<>();
        for (int i = DAILY_SLOT_START_HOUR; i < DAILY_SLOT_END_HOUR; i++) {
            LocalTime slotStart = LocalTime.of(i, 0);
            LocalTime slotEnd = LocalTime.of(i + 1, 0);
            boolean busy = mine.stream().anyMatch(r -> r.getStartTime().isBefore(slotEnd) && r.getEndTime().isAfter(slotStart));
            if (busy) {
                occupied.add(i);
            }
        }
        return DAILY_SLOT_END_HOUR - DAILY_SLOT_START_HOUR - occupied.size();
    }

    /** 预约实体 → 占用时段 VO（时间统一 HH:mm）；详情路径（无缓存）按身份裁剪：
     *  仅管理员或本人可见用途与 mine 标记，其余学生时段仍可查看但 purpose 为 null */
    private OccupiedSlotVO toSlotVO(Reservation r, boolean isAdmin) {
        OccupiedSlotVO slot = new OccupiedSlotVO();
        slot.setStartTime(TimeUtil.formatTime(r.getStartTime()));
        slot.setEndTime(TimeUtil.formatTime(r.getEndTime()));
        boolean mine = UserContext.isSelf(r.getUserId());
        slot.setMine(mine);
        slot.setPurpose((isAdmin || mine) ? r.getPurpose() : null);
        return slot;
    }

    /** 预约实体 → 占用时段 VO（列表路径专用）：共享缓存不含用户维度，只带时段、不带用途与 mine */
    private OccupiedSlotVO toSlotVOForList(Reservation r) {
        OccupiedSlotVO slot = new OccupiedSlotVO();
        slot.setStartTime(TimeUtil.formatTime(r.getStartTime()));
        slot.setEndTime(TimeUtil.formatTime(r.getEndTime()));
        return slot;   // purpose / mine 恒为 null
    }

    /**
     * 组装学生端教室列表缓存 Key：cache:classroom:list:v2:{分页与筛选参数指纹}；
     * 空参数以 "-" 占位，保证不同筛选/分页/日期条件互不串缓存。
     * Key 含版本号：R5 后列表数据不再携带用途（依赖请求者身份的字段不得进共享缓存），
     * 旧版缓存（部署前写入）的 occupiedSlots 仍含他人 purpose、命中分支直接 return 会继续泄露最多一个 TTL（60s），
     * 升级版本号即让旧 Key 全部失效，无需人工清缓存。
     */
    private String classroomListKey(long page, long size, String keyword, String building, Integer type, String date) {
        return RedisCache.CLASSROOM_LIST_KEY_PREFIX + LIST_CACHE_VERSION
                + page + ":" + size + ":"
                + (StrUtil.isBlank(keyword) ? "-" : keyword.trim()) + ":"
                + (StrUtil.isBlank(building) ? "-" : building.trim()) + ":"
                + (type == null ? "-" : type) + ":"
                + (StrUtil.isBlank(date) ? "-" : date.trim());
    }

    /**
     * 教室表单校验（新增/编辑共用）：名称/楼栋/编号/类型/容量必填，容量 > 0，类型合法
     */
    private void validateClassroomDTO(ClassroomDTO dto) {
        if (StrUtil.hasBlank(dto.getName(), dto.getBuilding(), dto.getRoomNo())) {
            throw new BusinessException("教室名称、楼栋、编号不能为空");
        }
        if (dto.getType() == null) {
            throw new BusinessException("教室类型不能为空");
        }
        if (dto.getType() != Constants.CLASSROOM_TYPE_NORMAL
                && dto.getType() != Constants.CLASSROOM_TYPE_LAB
                && dto.getType() != Constants.CLASSROOM_TYPE_COMPUTER) {
            throw new BusinessException("教室类型不合法（1-普通教室，2-实验室，3-机房）");
        }
        if (dto.getCapacity() == null) {
            throw new BusinessException("教室容量不能为空");
        }
        if (dto.getCapacity() <= 0) {
            throw new BusinessException("教室容量必须大于 0");
        }
    }
}
