package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.UserContext;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.UserFavorite;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.UserFavoriteMapper;
import com.example.reservation.service.FavoriteService;
import com.example.reservation.vo.FavoriteVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 收藏业务实现（R4 收藏全链路）
 * 规则（禁止简化）：
 *  1. toggle 语义：已收藏 → 取消收藏；未收藏 → 新增收藏；
 *  2. 上限：每人最多收藏 10 间教室（需求文档 1.4），超出拒绝并提示；
 *  3. user_favorite 表联合唯一索引 idx_user_class 兜底防重复。
 *
 * @author reservation-team
 */
@Service
public class FavoriteServiceImpl implements FavoriteService {

    @Resource
    private UserFavoriteMapper favoriteMapper;

    @Resource
    private ClassroomMapper classroomMapper;

    @Override
    public Boolean toggleFavorite(Long classroomId) {
        Long userId = UserContext.getUserId();
        if (classroomId == null) {
            throw new BusinessException("教室 ID 不能为空");
        }
        Classroom room = classroomMapper.selectById(classroomId);
        if (room == null) {
            throw new BusinessException("教室不存在");
        }
        // 已收藏 → 取消收藏（toggle 取消分支）
        UserFavorite exist = favoriteMapper.selectOne(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getClassroomId, classroomId));
        if (exist != null) {
            favoriteMapper.deleteById(exist.getId());
            return false;
        }
        // 未收藏 → 新增收藏，先校验上限（需求文档 1.4：每人最多收藏 10 间教室）
        Long count = favoriteMapper.selectCount(
                new LambdaQueryWrapper<UserFavorite>().eq(UserFavorite::getUserId, userId));
        if (count >= Constants.FAVORITE_MAX_COUNT) {
            throw new BusinessException("收藏数量已达上限（" + Constants.FAVORITE_MAX_COUNT + " 间），请先取消部分收藏");
        }
        UserFavorite favorite = new UserFavorite();
        favorite.setUserId(userId);
        favorite.setClassroomId(classroomId);
        favoriteMapper.insert(favorite);
        return true;
    }

    @Override
    public List<FavoriteVO> listFavorites() {
        Long userId = UserContext.getUserId();
        List<UserFavorite> favorites = favoriteMapper.selectList(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .orderByDesc(UserFavorite::getCreateTime));
        // 批量补全教室展示字段（一次查询避免 N+1）
        List<Long> classroomIds = favorites.stream().map(UserFavorite::getClassroomId).toList();
        Map<Long, Classroom> roomMap = classroomIds.isEmpty() ? Map.of()
                : classroomMapper.selectBatchIds(classroomIds).stream()
                        .collect(Collectors.toMap(Classroom::getId, Function.identity()));
        return favorites.stream().map(f -> {
            FavoriteVO vo = new FavoriteVO();
            vo.setId(f.getId());
            vo.setClassroomId(f.getClassroomId());
            vo.setCreateTime(f.getCreateTime());
            Classroom room = roomMap.get(f.getClassroomId());
            if (room != null) {
                vo.setName(room.getName());
                vo.setBuilding(room.getBuilding());
                vo.setRoomNo(room.getRoomNo());
                vo.setType(room.getType());
                vo.setCapacity(room.getCapacity());
            }
            return vo;
        }).toList();
    }
}
