package com.example.reservation.service;

import com.example.reservation.common.PageResult;
import com.example.reservation.dto.AuditDTO;
import com.example.reservation.dto.BatchAuditDTO;
import com.example.reservation.dto.ReservationDTO;
import com.example.reservation.vo.CalendarVO;
import com.example.reservation.vo.ConflictVO;
import com.example.reservation.vo.ReservationManageVO;
import com.example.reservation.vo.ReservationVO;

import java.util.List;

/**
 * 预约业务接口（R3 预约核心）
 *
 * @author reservation-team
 */
public interface ReservationService {

    /**
     * 冲突检测：同一教室、同一日期下，新预约与【已通过】预约时间段重叠即冲突
     * 重叠判定公式（需求文档 1.4）：新开始 < 旧结束 AND 新结束 > 旧开始
     */
    ConflictVO checkConflict(Long classroomId, String date, String startTime, String endTime);

    /**
     * 提交预约：校验必填/时间先后/教室存在且可用，后端二次冲突检测兜底；
     * 成功后状态为待审核(0)
     *
     * @return 新预约 ID
     */
    Long createReservation(ReservationDTO dto);

    /**
     * 我的预约列表（仅当前用户；支持状态筛选；附带教室名称/楼栋/编号展示字段）
     */
    PageResult<ReservationVO> pageMine(long page, long size, Integer status);

    /**
     * 取消预约：仅本人；待审核(0)/已通过(1) → 已取消(3)；
     * 预约开始前 1 小时内禁止取消；已驳回/已取消不可再取消
     */
    void cancelReservation(Long id);

    /**
     * 管理员：全量预约查询（状态、日期范围、关键词含用户账号/姓名/教室名称）
     */
    PageResult<ReservationManageVO> pageManage(long page, long size, Integer status,
                                               String startDate, String endDate, String keyword);

    /**
     * 审核：仅待审核(0)可审核；通过 → 已通过(1)，驳回 → 已驳回(2) 且必填审核备注；
     * 记录 auditorId / auditTime
     */
    void auditReservation(Long id, AuditDTO dto);

    /**
     * 批量审核：仅待审核记录可参与；批量通过/批量驳回
     *
     * @return 实际更新条数
     */
    int batchAudit(BatchAuditDTO dto);

    /**
     * 日历总览区间查询（R5 亮点功能，需求文档 2.4 第 7 页）
     * 返回区间内全部状态预约（色块数据），附带教室展示字段；
     * classroomId 为空表示全部教室；startDate/endDate 必填且跨度 ≤ 366 天
     */
    List<CalendarVO> listCalendar(Long classroomId, String startDate, String endDate);
}
