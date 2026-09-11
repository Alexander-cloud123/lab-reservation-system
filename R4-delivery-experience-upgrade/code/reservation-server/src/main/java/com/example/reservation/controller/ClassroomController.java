package com.example.reservation.controller;

import com.example.reservation.common.PageResult;
import com.example.reservation.common.Result;
import com.example.reservation.dto.BatchStatusDTO;
import com.example.reservation.dto.ClassroomDTO;
import com.example.reservation.dto.ClassroomStatusDTO;
import com.example.reservation.entity.Classroom;
import com.example.reservation.service.ClassroomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教室模块控制器（R2：管理端教室资源管理）
 * 管理员专属接口，拦截器按 /api/classroom/manage 前缀校验角色，学生 Token 访问返回 403
 *
 * @author reservation-team
 */
@Tag(name = "教室模块", description = "管理端教室资源管理（分页搜索/新增/编辑/删除/状态/批量）")
@RestController
@RequestMapping("/api/classroom/manage")
public class ClassroomController {

    @Resource
    private ClassroomService classroomService;

    /**
     * 教室分页列表 + 多条件搜索（关键词：名称/编号；楼栋、类型、状态筛选）
     */
    @Operation(summary = "教室管理-分页列表", description = "关键词（名称/编号）+楼栋+类型+状态筛选，按 create_time DESC 排序")
    @GetMapping
    public Result<PageResult<Classroom>> pageClassrooms(@RequestParam(defaultValue = "1") long page,
                                                        @RequestParam(defaultValue = "10") long size,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) String building,
                                                        @RequestParam(required = false) Integer type,
                                                        @RequestParam(required = false) Integer status) {
        return Result.success(classroomService.pageClassrooms(page, size, keyword, building, type, status));
    }

    /**
     * 新增教室（校验：名称/楼栋/编号/类型/容量必填，容量 > 0）
     */
    @Operation(summary = "教室管理-新增", description = "名称/楼栋/编号/类型/容量必填，容量>0；默认可用状态")
    @PostMapping
    public Result<Long> createClassroom(@RequestBody ClassroomDTO dto) {
        Long id = classroomService.createClassroom(dto);
        return Result.success("新增成功", id);
    }

    /**
     * 编辑教室（校验规则与新增一致）
     */
    @Operation(summary = "教室管理-编辑", description = "按 id 更新教室信息，校验规则与新增一致")
    @PutMapping
    public Result<Void> updateClassroom(@RequestBody ClassroomDTO dto) {
        classroomService.updateClassroom(dto);
        return Result.<Void>success("修改成功", null);
    }

    /**
     * 删除教室（存在任何预约记录时禁止删除，返回 400 提示）
     */
    @Operation(summary = "教室管理-删除", description = "存在任何预约记录时禁止删除，返回 400；无预约记录可删除")
    @DeleteMapping("/{id}")
    public Result<Void> deleteClassroom(@PathVariable Long id) {
        classroomService.deleteClassroom(id);
        return Result.<Void>success("删除成功", null);
    }

    /**
     * 启用/停用教室
     */
    @Operation(summary = "教室管理-启用/停用", description = "status：0-停用，1-可用")
    @PutMapping("/{id}/status")
    public Result<Void> updateClassroomStatus(@PathVariable Long id, @RequestBody ClassroomStatusDTO dto) {
        classroomService.updateClassroomStatus(id, dto.getStatus());
        return Result.<Void>success("操作成功", null);
    }

    /**
     * 批量启用/停用教室（入参：ids + status）
     */
    @Operation(summary = "教室管理-批量启用/停用", description = "入参 ids 列表 + status（0-停用，1-可用）")
    @PostMapping("/batch-status")
    public Result<Integer> batchUpdateStatus(@RequestBody BatchStatusDTO dto) {
        int updated = classroomService.batchUpdateStatus(dto.getIds(), dto.getStatus());
        return Result.success("批量操作成功", updated);
    }
}
