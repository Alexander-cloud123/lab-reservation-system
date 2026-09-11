package com.example.reservation.controller;

import com.example.reservation.common.PageResult;
import com.example.reservation.common.Result;
import com.example.reservation.service.ClassroomService;
import com.example.reservation.vo.ClassroomVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教室模块-学生端控制器（R3 教室浏览）
 * 路径 /api/classroom/list、/api/classroom/{id}，学生/管理员登录均可访问；
 * 与管理员专属接口 /api/classroom/manage 前缀区分（拦截器仅对 manage 前缀校验角色）
 *
 * @author reservation-team
 */
@Tag(name = "教室模块（学生端）", description = "教室浏览：分页列表（实时状态标签/日期筛选）、详情（时段占用）")
@RestController
@RequestMapping("/api/classroom")
public class ClassroomStudentController {

    @Resource
    private ClassroomService classroomService;

    /**
     * 学生端：教室分页列表 + 多条件筛选（关键词：名称/编号；楼栋、类型；可选日期）
     * 返回实时状态标签（当前空闲/使用中）与指定日期已通过预约占用时段
     */
    @Operation(summary = "教室浏览-分页列表", description = "仅返回可用教室；关键词（名称/编号）+楼栋+类型+可选日期；含实时状态标签")
    @GetMapping("/list")
    public Result<PageResult<ClassroomVO>> pageClassrooms(@RequestParam(defaultValue = "1") long page,
                                                          @RequestParam(defaultValue = "10") long size,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(required = false) String building,
                                                          @RequestParam(required = false) Integer type,
                                                          @RequestParam(required = false) String date) {
        return Result.success(classroomService.pageClassroomsForStudent(page, size, keyword, building, type, date));
    }

    /**
     * 学生端：教室详情 + 指定日期（默认当天）该教室已通过预约时段占用列表
     */
    @Operation(summary = "教室浏览-详情", description = "详情 + 指定日期（默认当天）已通过预约时段占用列表，供前端展示与冲突校验")
    @GetMapping("/{id}")
    public Result<ClassroomVO> getClassroomDetail(@PathVariable Long id,
                                                  @RequestParam(required = false) String date) {
        return Result.success(classroomService.getClassroomDetail(id, date));
    }
}
