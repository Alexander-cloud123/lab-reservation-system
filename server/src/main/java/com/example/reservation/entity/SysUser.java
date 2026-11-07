package com.example.reservation.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体（sys_user）
 * role：0-学生，1-管理员；status：0-禁用，1-正常；password 为 BCrypt 密文
 *
 * @author hyr
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录账号（唯一） */
    private String username;

    /** 密码（BCrypt 加密） */
    private String password;

    /** 真实姓名 */
    private String name;

    /** 学号（学生角色必填） */
    private String studentNo;

    /** 联系电话 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 角色：0-学生，1-管理员 */
    private Integer role;

    /** 状态：0-禁用，1-正常 */
    private Integer status;

    /**
     * Token 版本号（登出 / 改密 / 管理员重置 / 禁用时自增）。
     * JWT 签发时携带该值，拦截器与库中当前值比对，不一致的旧 Token 立即失效——
     * 使服务端撤销不依赖 Redis（Redis 关闭、进程重启、多实例部署下同样成立）。
     */
    private Integer tokenVersion;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间（自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
