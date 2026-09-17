package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.UserContext;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.SysUser;
import com.example.reservation.entity.UserFavorite;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.mapper.UserFavoriteMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收藏上限并发修复（R1）单元测试（纯 Mockito，不依赖数据库）。
 * 断言策略：不断言 LambdaQueryWrapper 内部 SQL 文本（MP 内部 API 不可靠），
 * 改为"方法级"断言——修复的本质是"改用 sys_user 行加锁读"，因此断言调用了 selectOne(FOR UPDATE)
 * 且其调用顺序先于 selectCount 与 insert。
 *
 * @author reservation-team
 */
@ExtendWith(MockitoExtension.class)
class FavoriteServiceImplTest {

    @Mock
    private UserFavoriteMapper favoriteMapper;

    @Mock
    private ClassroomMapper classroomMapper;

    @Mock
    private SysUserMapper userMapper;

    @InjectMocks
    private FavoriteServiceImpl favoriteService;

    /**
     * 纯 Mockito 环境不经过 Spring 启动，MyBatis-Plus 的 Lambda 元数据需手动初始化，
     * 否则构造 LambdaQueryWrapper 即抛 "can not find lambda cache for this entity"
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), SysUser.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), UserFavorite.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Classroom.class);
    }

    @BeforeEach
    void setUp() {
        // 模拟登录态（学生 1 号）
        UserContext.set(1L, "student01", Constants.ROLE_STUDENT);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /* ===== R1：收藏上限并发修复（sys_user 行锁先于计数与插入） ===== */

    @Test
    @DisplayName("R1：加锁读（selectOne FOR UPDATE）先于计数与插入，计数 9 时新增成功")
    void toggleFavorite_locksUserBeforeCountAndInsert() {
        when(userMapper.selectOne(any())).thenReturn(new SysUser());
        when(favoriteMapper.selectOne(any())).thenReturn(null);
        when(classroomMapper.selectById(any())).thenReturn(enabledRoom());
        when(favoriteMapper.selectCount(any())).thenReturn(9L);

        Boolean result = favoriteService.toggleFavorite(1L);

        assertTrue(result);
        // 修复本质：对 sys_user 行加锁（selectOne FOR UPDATE）必须是事务第一条语句，
        // 且加锁先于计数与插入——否则先普通读建立旧 read view，锁内计数仍读旧快照、上限可被突破
        InOrder inOrder = inOrder(userMapper, favoriteMapper);
        inOrder.verify(userMapper).selectOne(any());
        inOrder.verify(favoriteMapper).selectCount(any());
        inOrder.verify(favoriteMapper).insert(any(UserFavorite.class));
    }

    @Test
    @DisplayName("R1：计数已达上限 10 时拒绝新增且不执行插入")
    void toggleFavorite_rejectsWhenLimitReached() {
        when(userMapper.selectOne(any())).thenReturn(new SysUser());
        when(favoriteMapper.selectOne(any())).thenReturn(null);
        when(classroomMapper.selectById(any())).thenReturn(enabledRoom());
        when(favoriteMapper.selectCount(any())).thenReturn(10L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> favoriteService.toggleFavorite(1L));

        assertTrue(ex.getMessage().contains("已达上限"));
        verify(favoriteMapper, never()).insert(any(UserFavorite.class));
    }

    /* ===== 测试数据构造 ===== */

    /** 预置一间启用中的教室 */
    private Classroom enabledRoom() {
        Classroom room = new Classroom();
        room.setId(1L);
        room.setStatus(Constants.CLASSROOM_STATUS_ENABLED);
        return room;
    }
}
