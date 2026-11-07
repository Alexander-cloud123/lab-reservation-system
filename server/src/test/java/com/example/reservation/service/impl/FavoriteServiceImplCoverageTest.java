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
import com.example.reservation.vo.FavoriteVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 收藏业务分支覆盖补充（R4 收藏全链路）
 *
 * <p>覆盖口径（需求 1.4 / 审查修复 H4 / M4）：
 * toggle 幂等、上限 10 间、教室不存在时的取消收藏、唯一索引冲突幂等、收藏列表过滤已删教室的幽灵卡片。
 */
@ExtendWith(MockitoExtension.class)
class FavoriteServiceImplCoverageTest {

    @Mock
    private UserFavoriteMapper favoriteMapper;

    @Mock
    private ClassroomMapper classroomMapper;

    @Mock
    private SysUserMapper userMapper;

    @InjectMocks
    private FavoriteServiceImpl favoriteService;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), UserFavorite.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), SysUser.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Classroom.class);
    }

    @BeforeEach
    void setUp() {
        UserContext.set(2L, "zhangsan", Constants.ROLE_STUDENT);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /* ==================== toggle ==================== */

    @Test
    @DisplayName("toggleFavorite：教室 ID 为空 / 用户行不存在（并发删除）被拒绝")
    void toggle_rejectsInvalidCases() {
        assertMessage("教室 ID 不能为空", () -> favoriteService.toggleFavorite(null));

        when(userMapper.selectOne(any())).thenReturn(null);
        assertMessage("用户不存在", () -> favoriteService.toggleFavorite(1L));
    }

    @Test
    @DisplayName("toggleFavorite：已收藏 → 取消；教室已删除也能取消（幂等，不查教室表）")
    void toggle_existing_removesRegardlessOfClassroom() {
        when(userMapper.selectOne(any())).thenReturn(user(2L));
        UserFavorite exist = favorite(9L, 1L);
        when(favoriteMapper.selectOne(any())).thenReturn(exist);

        assertFalse(favoriteService.toggleFavorite(1L));
        verify(favoriteMapper).deleteById(9L);
        verifyNoInteractions(classroomMapper);
    }

    @Test
    @DisplayName("toggleFavorite：未收藏但教室不存在 → 拒绝新增")
    void toggle_roomMissing_rejected() {
        when(userMapper.selectOne(any())).thenReturn(user(2L));
        when(favoriteMapper.selectOne(any())).thenReturn(null);
        when(classroomMapper.selectOne(any())).thenReturn(null);

        assertMessage("教室不存在", () -> favoriteService.toggleFavorite(1L));
        verify(favoriteMapper, never()).insert(any(UserFavorite.class));
    }

    @Test
    @DisplayName("toggleFavorite：收藏数已达 10 间上限 → 拒绝新增")
    void toggle_atMaxCount_rejected() {
        when(userMapper.selectOne(any())).thenReturn(user(2L));
        when(favoriteMapper.selectOne(any())).thenReturn(null);
        when(classroomMapper.selectOne(any())).thenReturn(room(1L));
        when(favoriteMapper.selectCount(any())).thenReturn((long) Constants.FAVORITE_MAX_COUNT);

        assertMessage("收藏数量已达上限", () -> favoriteService.toggleFavorite(1L));
        verify(favoriteMapper, never()).insert(any(UserFavorite.class));
    }

    @Test
    @DisplayName("toggleFavorite：唯一索引冲突（并发重复收藏）→ 幂等返回 true，不抛 500")
    void toggle_duplicateKey_isIdempotent() {
        when(userMapper.selectOne(any())).thenReturn(user(2L));
        when(favoriteMapper.selectOne(any())).thenReturn(null);
        when(classroomMapper.selectOne(any())).thenReturn(room(1L));
        when(favoriteMapper.selectCount(any())).thenReturn(0L);
        when(favoriteMapper.insert(any(UserFavorite.class))).thenThrow(new DuplicateKeyException("duplicate"));

        assertTrue(favoriteService.toggleFavorite(1L));
    }

    @Test
    @DisplayName("toggleFavorite：正常新增返回 true")
    void toggle_insertsSuccessfully() {
        when(userMapper.selectOne(any())).thenReturn(user(2L));
        when(favoriteMapper.selectOne(any())).thenReturn(null);
        when(classroomMapper.selectOne(any())).thenReturn(room(1L));
        when(favoriteMapper.selectCount(any())).thenReturn(0L);

        assertTrue(favoriteService.toggleFavorite(1L));
        verify(favoriteMapper).insert(any(UserFavorite.class));
    }

    /* ==================== 列表 ==================== */

    @Test
    @DisplayName("listFavorites：无收藏时不查教室表，返回空列表")
    void listFavorites_empty() {
        when(favoriteMapper.selectList(any())).thenReturn(List.of());

        assertTrue(favoriteService.listFavorites().isEmpty());
        verify(classroomMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("listFavorites：批量补全教室信息，并过滤教室已删除的幽灵收藏")
    void listFavorites_mapsAndFiltersGhosts() {
        UserFavorite kept = favorite(1L, 1L);
        UserFavorite ghost = favorite(2L, 99L);
        when(favoriteMapper.selectList(any())).thenReturn(List.of(kept, ghost));
        when(classroomMapper.selectBatchIds(any())).thenReturn(List.of(room(1L)));

        List<FavoriteVO> result = favoriteService.listFavorites();

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getClassroomId());
        assertEquals("A101", result.get(0).getName());
        assertEquals("信息楼", result.get(0).getBuilding());
        assertEquals("A1", result.get(0).getRoomNo());
        assertEquals(Constants.CLASSROOM_TYPE_NORMAL, result.get(0).getType());
        assertEquals(60, result.get(0).getCapacity());
    }

    /* ==================== 工具方法 ==================== */

    private static SysUser user(Long id) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername("zhangsan");
        return u;
    }

    private static Classroom room(Long id) {
        Classroom c = new Classroom();
        c.setId(id);
        c.setName("A101");
        c.setBuilding("信息楼");
        c.setRoomNo("A1");
        c.setType(Constants.CLASSROOM_TYPE_NORMAL);
        c.setCapacity(60);
        return c;
    }

    private static UserFavorite favorite(Long id, Long classroomId) {
        UserFavorite f = new UserFavorite();
        f.setId(id);
        f.setUserId(2L);
        f.setClassroomId(classroomId);
        f.setCreateTime(LocalDateTime.of(2026, 11, 1, 10, 0));
        return f;
    }

    private static void assertMessage(String expectedPart, Runnable action) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        assertTrue(ex.getMessage().contains(expectedPart), "实际消息：" + ex.getMessage());
    }
}
