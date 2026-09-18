package com.example.reservation.service.converter;

import com.example.reservation.common.Constants;
import com.example.reservation.common.TimeUtil;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.vo.ReservationExportVO;
import com.example.reservation.vo.ReservationManageVO;
import com.example.reservation.vo.ReservationVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 预约实体 → VO 转换器
 * 由 ReservationServiceImpl 的私有转换方法外移而来（代码质量重构轮），行为不变：
 * 批量预查教室/用户信息，避免逐行 selectById 造成 N+1 查询
 *
 * @author reservation-team
 */
@Component
public class ReservationConverter {

    @Resource
    private ClassroomMapper classroomMapper;

    @Resource
    private SysUserMapper userMapper;

    /** 批量预查教室信息 → id→Classroom Map（空集合安全，避免逐行 selectById 造成 N+1） */
    public Map<Long, Classroom> batchClassroomMap(List<Reservation> list) {
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
    public Map<Long, SysUser> batchUserMap(List<Reservation> list) {
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
    public ReservationVO toReservationVO(Reservation r, Map<Long, Classroom> roomMap) {
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
    public ReservationManageVO toManageVO(Reservation r, Map<Long, SysUser> userMap, Map<Long, Classroom> roomMap) {
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
    public ReservationExportVO toExportVO(Reservation r, Map<Long, SysUser> userMap, Map<Long, Classroom> roomMap) {
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
