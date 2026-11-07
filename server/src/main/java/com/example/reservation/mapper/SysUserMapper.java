package com.example.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.reservation.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户 Mapper
 *
 * @author hyr
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 原子自增 Token 版本号（撤销该用户全部已签发 Token）。
     *
     * <p>用单条 UPDATE 自增而非「先读后写」：并发撤销不会互相覆盖丢失更新；
     * 该语句只改版本号，不动 update_time（版本号不是业务数据，无需参与时间线）。</p>
     *
     * @param id 用户 ID
     * @return 受影响行数；0 表示用户不存在
     */
    @Update("UPDATE sys_user SET token_version = token_version + 1 WHERE id = #{id}")
    int bumpTokenVersion(@Param("id") Long id);
}
