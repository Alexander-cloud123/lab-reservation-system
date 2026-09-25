package com.example.reservation.service;

import com.example.reservation.common.PageResult;
import com.example.reservation.dto.ClassroomDTO;
import com.example.reservation.entity.Classroom;
import com.example.reservation.vo.ClassroomVO;

import java.util.List;

/**
 * 教室业务接口（R2：管理端教室资源管理；R3：学生端教室浏览）
 *
 * @author reservation-team
 */
public interface ClassroomService {

    /**
     * 管理员：分页查询教室列表（关键词：名称/编号；楼栋、类型、状态筛选）
     * 排序按 create_time DESC
     */
    PageResult<Classroom> pageClassrooms(long page, long size, String keyword, String building, Integer type, Integer status);

    /**
     * 管理员：新增教室（名称/楼栋/编号/类型/容量必填，容量 > 0；默认可用状态）
     *
     * @return 新教室 ID
     */
    Long createClassroom(ClassroomDTO dto);

    /**
     * 管理员：编辑教室（校验规则与新增一致）
     */
    void updateClassroom(ClassroomDTO dto);

    /**
     * 管理员：删除教室（存在任何预约记录时禁止删除，返回 400）
     */
    void deleteClassroom(Long id);

    /**
     * 管理员：启用/停用教室
     */
    void updateClassroomStatus(Long id, Integer status);

    /**
     * 管理员：批量启用/停用教室
     *
     * @return 实际更新条数
     */
    int batchUpdateStatus(List<Long> ids, Integer status);

    /**
     * 学生端：分页查询可用教室（关键词：名称/编号；楼栋、类型、可选日期筛选）
     * 返回实时状态标签（当前空闲/使用中）与指定日期已通过预约占用时段
     */
    PageResult<ClassroomVO> pageClassroomsForStudent(long page, long size, String keyword,
                                                     String building, Integer type, String date);

    /**
     * 学生端：教室详情 + 指定日期（默认当天）已通过预约时段占用列表
     */
    ClassroomVO getClassroomDetail(Long id, String date);
}
