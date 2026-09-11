package com.example.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.reservation.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper
 *
 * @author reservation-team
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
