package com.example.reservation.vo;

import com.example.reservation.entity.SysUser;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信息视图对象（脱敏：不含 password 字段）
 *
 * @author reservation-team
 */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String name;

    private String studentNo;

    private String phone;

    private String email;

    private Integer role;

    private Integer status;

    private LocalDateTime createTime;

    /** 从实体转换（剔除密码） */
    public static UserVO from(SysUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setName(user.getName());
        vo.setStudentNo(user.getStudentNo());
        vo.setPhone(user.getPhone());
        vo.setEmail(user.getEmail());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());
        return vo;
    }
}
