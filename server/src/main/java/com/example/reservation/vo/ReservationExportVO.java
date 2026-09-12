package com.example.reservation.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 预约记录导出视图对象（R6：管理端预约记录页 Excel 导出）
 * 仅含展示字段（白名单），不含 password 等敏感信息；
 * 时间字段统一输出为字符串，避免 EasyExcel 日期格式歧义
 *
 * @author reservation-team
 */
@Data
public class ReservationExportVO {

    /** 预约 ID */
    @ExcelProperty("预约ID")
    private Long id;

    /** 教室名称 */
    @ExcelProperty("教室名称")
    private String classroomName;

    /** 所属楼栋 */
    @ExcelProperty("楼栋")
    private String building;

    /** 教室编号 */
    @ExcelProperty("教室编号")
    private String roomNo;

    /** 预约用户账号 */
    @ExcelProperty("用户账号")
    private String userAccount;

    /** 预约用户姓名 */
    @ExcelProperty("用户姓名")
    private String userName;

    /** 预约日期（yyyy-MM-dd） */
    @ExcelProperty("预约日期")
    private String reserveDate;

    /** 开始时间（HH:mm） */
    @ExcelProperty("开始时间")
    private String startTime;

    /** 结束时间（HH:mm） */
    @ExcelProperty("结束时间")
    private String endTime;

    /** 预约用途 */
    @ExcelProperty("预约用途")
    private String purpose;

    /** 状态文案（待审核/已通过/已驳回/已取消，与前端状态标签口径一致） */
    @ExcelProperty("状态")
    private String statusText;

    /** 审核备注（驳回原因） */
    @ExcelProperty("审核备注")
    private String auditRemark;

    /** 审核时间（yyyy-MM-dd HH:mm:ss） */
    @ExcelProperty("审核时间")
    private String auditTime;

    /** 创建时间（yyyy-MM-dd HH:mm:ss） */
    @ExcelProperty("创建时间")
    private String createTime;
}
