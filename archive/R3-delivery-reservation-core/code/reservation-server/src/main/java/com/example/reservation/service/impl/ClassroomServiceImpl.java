package com.example.reservation.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.PageResult;
import com.example.reservation.common.TimeUtil;
import com.example.reservation.dto.ClassroomDTO;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.service.ClassroomService;
import com.example.reservation.vo.ClassroomVO;
import com.example.reservation.vo.OccupiedSlotVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 教室业务实现
 * 删除保护规则（R2）：该教室存在任何预约记录时禁止删除，返回 400 提示
 *
 * @author reservation-team
 */
@Service
public class ClassroomServiceImpl implements ClassroomService {

    @Resource
    private ClassroomMapper classroomMapper;

    @Resource
    private ReservationMapper reservationMapper;

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
    }

    @Override
    public void deleteClassroom(Long id) {
        if (id == null) {
            throw new BusinessException("教室 ID 不能为空");
        }
        Classroom exists = classroomMapper.selectById(id);
        if (exists == null) {
            throw new BusinessException("教室不存在");
        }
        // 删除保护：该教室存在任何预约记录（不限状态）时禁止删除
        Long reservationCount = reservationMapper.selectCount(
                new LambdaQueryWrapper<Reservation>().eq(Reservation::getClassroomId, id));
        if (reservationCount > 0) {
            throw new BusinessException("该教室存在预约记录，禁止删除");
        }
        classroomMapper.deleteById(id);
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
        return classroomMapper.update(null, wrapper);
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
                            Collectors.mapping(this::toSlotVO, Collectors.toList())));
        } else {
            occupiedMap = Map.of();
        }

        List<ClassroomVO> vos = result.getRecords().stream().map(c -> {
            ClassroomVO vo = toStudentVO(c);
            vo.setStatusLabel(calcStatusLabel(c.getId(), todayApproved));
            vo.setOccupiedSlots(occupiedMap.getOrDefault(c.getId(), List.of()));
            return vo;
        }).toList();
        return new PageResult<>(result.getTotal(), vos);
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
        vo.setOccupiedSlots(dayApproved.stream().map(this::toSlotVO).toList());
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
     * 实时状态标签口径（需求文档 1.3 冲优项，交付说明标注）：
     * 以当天为基准——存在已通过预约且当前时刻 ∈ [开始, 结束) → 使用中；否则当前空闲
     */
    private String calcStatusLabel(Long classroomId, List<Reservation> todayApproved) {
        LocalTime now = LocalTime.now();
        boolean inUse = todayApproved.stream()
                .filter(r -> r.getClassroomId().equals(classroomId))
                .anyMatch(r -> !now.isBefore(r.getStartTime()) && now.isBefore(r.getEndTime()));
        return inUse ? Constants.STATUS_LABEL_IN_USE : Constants.STATUS_LABEL_FREE;
    }

    /** 预约实体 → 占用时段 VO（时间统一 HH:mm） */
    private OccupiedSlotVO toSlotVO(Reservation r) {
        OccupiedSlotVO slot = new OccupiedSlotVO();
        slot.setStartTime(TimeUtil.formatTime(r.getStartTime()));
        slot.setEndTime(TimeUtil.formatTime(r.getEndTime()));
        slot.setPurpose(r.getPurpose());
        return slot;
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
