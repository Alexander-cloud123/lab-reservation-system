package com.example.reservation.controller;

import com.example.reservation.common.PageResult;
import com.example.reservation.common.Result;
import com.example.reservation.dto.AuditDTO;
import com.example.reservation.dto.BatchAuditDTO;
import com.example.reservation.dto.ReservationDTO;
import com.example.reservation.service.ReservationService;
import com.example.reservation.vo.CalendarVO;
import com.example.reservation.vo.ConflictVO;
import com.example.reservation.vo.ReservationExportVO;
import com.example.reservation.vo.ReservationManageVO;
import com.example.reservation.vo.ReservationVO;
import com.alibaba.excel.EasyExcel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 预约模块控制器（R3 预约核心）
 * 学生接口：conflict / 提交 / mine / cancel（登录即可访问）；
 * 管理员接口：manage（拦截器前缀校验）/ audit / batch-audit（拦截器精确路径校验），学生 Token 访问返回 403
 *
 * @author reservation-team
 */
@Tag(name = "预约模块", description = "冲突检测、提交预约、我的预约、取消、管理员审核与批量审核")
@RestController
@RequestMapping("/api/reservation")
public class ReservationController {

    @Resource
    private ReservationService reservationService;

    /**
     * 冲突检测：教室 + 日期 + 时段 → 是否与已通过预约冲突
     */
    @Operation(summary = "预约-冲突检测", description = "同一教室同一日期与已通过预约重叠即冲突；公式：新开始<旧结束 AND 新结束>旧开始")
    @GetMapping("/conflict")
    public Result<ConflictVO> checkConflict(@RequestParam Long classroomId,
                                            @RequestParam String date,
                                            @RequestParam String startTime,
                                            @RequestParam String endTime) {
        return Result.success(reservationService.checkConflict(classroomId, date, startTime, endTime));
    }

    /**
     * 提交预约：后端二次冲突检测兜底；成功后状态为待审核(0)
     */
    @Operation(summary = "预约-提交", description = "校验教室/日期/时段/用途必填、开始<结束；后端二次冲突检测（防绕过前端）")
    @PostMapping
    public Result<Long> createReservation(@RequestBody ReservationDTO dto) {
        Long id = reservationService.createReservation(dto);
        return Result.success("预约提交成功，待管理员审核", id);
    }

    /**
     * 我的预约列表（支持状态筛选；附带教室名称/楼栋/编号展示字段）
     */
    @Operation(summary = "预约-我的预约", description = "仅当前用户预约，支持状态筛选，返回关联教室展示字段")
    @GetMapping("/mine")
    public Result<PageResult<ReservationVO>> pageMine(@RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "10") long size,
                                                      @RequestParam(required = false) Integer status) {
        return Result.success(reservationService.pageMine(page, size, status));
    }

    /**
     * 取消预约：仅本人；待审核/已通过 → 已取消；开始前 1 小时内禁止取消
     */
    @Operation(summary = "预约-取消", description = "仅本人可取消；待审核(0)/已通过(1)→已取消(3)；开始前 1 小时内禁止取消")
    @PutMapping("/{id}/cancel")
    public Result<Void> cancelReservation(@PathVariable Long id) {
        reservationService.cancelReservation(id);
        return Result.<Void>success("取消成功", null);
    }

    /**
     * 管理员：全量预约查询（状态、日期范围、教室、关键词含用户账号/姓名/教室名称）
     * R6：新增 classroomId 可选参数，支撑预约记录页「教室」筛选
     */
    @Operation(summary = "预约管理-全量查询", description = "状态/日期范围/教室/关键词（用户账号、姓名、教室名称）筛选，按 create_time DESC")
    @GetMapping("/manage")
    public Result<PageResult<ReservationManageVO>> pageManage(@RequestParam(defaultValue = "1") long page,
                                                              @RequestParam(defaultValue = "10") long size,
                                                              @RequestParam(required = false) Integer status,
                                                              @RequestParam(required = false) String startDate,
                                                              @RequestParam(required = false) String endDate,
                                                              @RequestParam(required = false) String keyword,
                                                              @RequestParam(required = false) Long classroomId) {
        return Result.success(reservationService.pageManage(page, size, status, startDate, endDate, keyword, classroomId));
    }

    /**
     * 管理员：预约记录导出（R6 预约记录页，需求文档 2.4 第 12 页）
     * 与全量查询同筛选口径，返回 xlsx 文件流；鉴权由拦截器管理员前缀统一处理（学生 403 / 未登录 401）
     */
    @Operation(summary = "预约管理-记录导出", description = "按筛选条件导出预约记录为 Excel（xlsx），返回文件流（非 Result 结构）")
    @GetMapping("/export")
    public void exportReservations(@RequestParam(required = false) Integer status,
                                   @RequestParam(required = false) String startDate,
                                   @RequestParam(required = false) String endDate,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) Long classroomId,
                                   HttpServletResponse response) throws IOException {
        List<ReservationExportVO> list = reservationService.listExport(status, startDate, endDate, keyword, classroomId);
        // 文件名带时间戳，避免同名覆盖；中文名按 RFC 5987 编码（filename*）兼容主流浏览器
        String fileName = "预约记录_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        EasyExcel.write(response.getOutputStream(), ReservationExportVO.class)
                .sheet("预约记录")
                .doWrite(list);
    }

    /**
     * 审核：仅待审核(0)可审核；通过 → 已通过(1)，驳回 → 已驳回(2) 且必填备注；记录 auditorId/auditTime
     */
    @Operation(summary = "预约管理-审核", description = "仅待审核可审核；驳回必填审核备注；记录审核人与审核时间")
    @PutMapping("/{id}/audit")
    public Result<Void> auditReservation(@PathVariable Long id, @RequestBody AuditDTO dto) {
        reservationService.auditReservation(id, dto);
        return Result.<Void>success("审核成功", null);
    }

    /**
     * 批量审核：仅待审核记录可参与；批量通过/批量驳回
     */
    @Operation(summary = "预约管理-批量审核", description = "入参 ids + status；仅待审核记录可参与；批量驳回必填备注；返回实际更新条数")
    @PostMapping("/batch-audit")
    public Result<Integer> batchAudit(@RequestBody BatchAuditDTO dto) {
        int updated = reservationService.batchAudit(dto);
        return Result.success("批量审核成功", updated);
    }

    /**
     * 日历总览区间查询（R5 亮点功能）：教室可选，区间必填，返回全部状态预约（色块数据）
     */
    @Operation(summary = "预约-日历总览区间查询", description = "classroomId 可选（空=全部教室）；startDate/endDate 必填且跨度 ≤366 天；返回区间内全部状态预约")
    @GetMapping("/calendar")
    public Result<List<CalendarVO>> listCalendar(@RequestParam(required = false) Long classroomId,
                                                 @RequestParam String startDate,
                                                 @RequestParam String endDate) {
        return Result.success(reservationService.listCalendar(classroomId, startDate, endDate));
    }
}
