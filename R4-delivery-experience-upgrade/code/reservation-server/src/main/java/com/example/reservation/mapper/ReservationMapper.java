package com.example.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.reservation.entity.Reservation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预约 Mapper
 *
 * @author reservation-team
 */
@Mapper
public interface ReservationMapper extends BaseMapper<Reservation> {
}
