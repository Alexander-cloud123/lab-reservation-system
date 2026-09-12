package com.example.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.reservation.entity.UserFavorite;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户收藏 Mapper
 *
 * @author reservation-team
 */
@Mapper
public interface UserFavoriteMapper extends BaseMapper<UserFavorite> {
}
