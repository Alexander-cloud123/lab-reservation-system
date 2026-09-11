package com.example.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.reservation.entity.Classroom;
import org.apache.ibatis.annotations.Mapper;

/**
 * 教室 Mapper
 *
 * @author reservation-team
 */
@Mapper
public interface ClassroomMapper extends BaseMapper<Classroom> {
}
