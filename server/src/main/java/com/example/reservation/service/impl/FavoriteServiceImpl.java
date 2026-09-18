package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.ClassroomValidator;
import com.example.reservation.common.Constants;
import com.example.reservation.common.UserContext;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.SysUser;
import com.example.reservation.entity.UserFavorite;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.mapper.UserFavoriteMapper;
import com.example.reservation.service.FavoriteService;
import com.example.reservation.vo.FavoriteVO;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 审查修复（2026-09-17）：
 *  - H4：取消收藏对"教室已不存在"幂等（先查收藏、后查教室），删除教室后的收藏可正常移除；
 *  - H4：收藏列表过滤掉教室已不存在的记录（杜绝"幽灵收藏卡"）；
 *  - M4/R1：新增收藏用 sys_user 数据库行锁串行化"计数+插入"（原用户维度 Redis 互斥锁方案已替换，
 *    行锁随事务释放、无 Redis 降级语义，且无 gap-lock 死锁风险），并发超限被阻止；唯一索引冲突捕获后幂等返回。
 *
 * @author reservation-team
 */
@Service
public class FavoriteServiceImpl implements FavoriteService {

    @Resource
    private UserFavoriteMapper favoriteMapper;

    @Resource
    private ClassroomMapper classroomMapper;

    @Resource
    private SysUserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean toggleFavorite(Long classroomId) {
        Long userId = UserContext.getUserId();
        if (classroomId == null) {
            throw new BusinessException("教室 ID 不能为空");
        }
        // R1 修复：锁定用户行（SELECT ... FOR UPDATE），串行化本人收藏的"计数+插入"。
        // 必须是本事务第一条语句——先普通读会建立旧 read view，锁内 selectCount 仍读旧快照，
        // 并发刚提交的插入不可见，上限依旧可被突破（与已修 H2 同一根因）。
        // 锁 sys_user 行而非 user_favorite 既有行：收藏数为 0 时对 idx_user_id 区间只会拿到 gap lock，
        // gap lock 互相兼容、双方 INSERT 的插入意向锁互撞 → 死锁；sys_user 行必然存在，是纯记录锁。
        // 行锁随事务提交/回滚自动释放，不再需要 Redis 锁与手工 unlock。
        SysUser lockedUser = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getId, userId).last("FOR UPDATE"));
        if (lockedUser == null) {
            throw new BusinessException("用户不存在");
        }

        // 先查收藏（H4 修复：取消分支不依赖教室存在——教室被删除后收藏记录仍需可移除，幂等语义）
        UserFavorite exist = favoriteMapper.selectOne(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getClassroomId, classroomId));
        if (exist != null) {
            // 已收藏 → 取消收藏（删除动作与教室存在性无关，杜绝"删除分支不可达"的幽灵收藏）
            favoriteMapper.deleteById(exist.getId());
            return false;
        }

        // 未收藏 → 新增收藏：教室必须存在且可用
        Classroom room = classroomMapper.selectById(classroomId);
        ClassroomValidator.requireExists(room);
        // 行锁串行化后，锁内计数为最新已提交值（前一个并发请求已完成的新增在此必可见）
        Long count = favoriteMapper.selectCount(
                new LambdaQueryWrapper<UserFavorite>().eq(UserFavorite::getUserId, userId));
        if (count >= Constants.FAVORITE_MAX_COUNT) {
            throw new BusinessException("收藏数量已达上限（" + Constants.FAVORITE_MAX_COUNT + " 间），请先取消部分收藏");
        }
        UserFavorite favorite = new UserFavorite();
        favorite.setUserId(userId);
        favorite.setClassroomId(classroomId);
        try {
            favoriteMapper.insert(favorite);
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：并发重复收藏同一教室时，后到者按"已收藏"幂等返回，不 500
            return true;
        }
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
        return favorites.stream()
                // H4 修复：过滤掉教室已不存在的收藏记录（删除教室时已联动清理，此处双保险，杜绝幽灵卡片）
                .filter(f -> roomMap.containsKey(f.getClassroomId()))
                .map(f -> {
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
