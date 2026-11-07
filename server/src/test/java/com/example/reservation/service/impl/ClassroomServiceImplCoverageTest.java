package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.UserContext;
import com.example.reservation.config.RedisCache;
import com.example.reservation.config.RedisProperties;
import com.example.reservation.dto.ClassroomDTO;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.UserFavorite;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.UserFavoriteMapper;
import com.example.reservation.vo.ClassroomVO;
import com.example.reservation.vo.OccupiedSlotVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 教室业务分支覆盖补充（与 ClassroomServiceImplTest 互补）
 *
 * <p>覆盖目标：管理端筛选与 CRUD 校验分支、删除保护、启停幂等、学生端列表缓存命中/回填与
 * 占用时段/剩余时段/状态标签计算、详情路径按身份裁剪用途。
 */
@ExtendWith(MockitoExtension.class)
class ClassroomServiceImplCoverageTest {

    @Mock
    private ClassroomMapper classroomMapper;

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private UserFavoriteMapper favoriteMapper;

    @Mock
    private RedisCache redisCache;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private RedisProperties redisProperties;

    @InjectMocks
    private ClassroomServiceImpl classroomService;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Classroom.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Reservation.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), UserFavorite.class);
    }

    @BeforeEach
    void setUp() {
        UserContext.set(1L, "student01", Constants.ROLE_STUDENT);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /* ==================== 管理端分页 ==================== */

    @Test
    @DisplayName("pageClassrooms：分页参数校验 + 带筛选条件查询")
    void pageClassrooms_validatesAndFilters() {
        assertMessage("页码必须大于等于 1", () -> classroomService.pageClassrooms(0, 10, null, null, null, null));
        assertMessage("每页条数必须在 1-500 之间",
                () -> classroomService.pageClassrooms(1, 501, null, null, null, null));

        Page<Classroom> page = new Page<>();
        when(classroomMapper.selectPage(any(), any())).thenReturn(page);
        var result = classroomService.pageClassrooms(1, 10, "A101", "信息楼", 1, Constants.CLASSROOM_STATUS_ENABLED);
        assertTrue(result.getRecords().isEmpty());
    }

    /* ==================== 新增 / 编辑 ==================== */

    @Test
    @DisplayName("createClassroom：必填 / 类型 / 容量 / 字段长度校验分支")
    void createClassroom_validationBranches() {
        assertMessage("教室名称、楼栋、编号不能为空", () -> classroomService.createClassroom(dto(null, "信息楼", "A101")));

        ClassroomDTO noType = dto("A101", "信息楼", "A101");
        noType.setType(null);
        assertMessage("教室类型不能为空", () -> classroomService.createClassroom(noType));

        ClassroomDTO invalidType = dto("A101", "信息楼", "A101");
        invalidType.setType(9);
        assertMessage("教室类型不合法", () -> classroomService.createClassroom(invalidType));

        ClassroomDTO noCapacity = dto("A101", "信息楼", "A101");
        noCapacity.setCapacity(null);
        assertMessage("教室容量不能为空", () -> classroomService.createClassroom(noCapacity));

        ClassroomDTO zeroCapacity = dto("A101", "信息楼", "A101");
        zeroCapacity.setCapacity(0);
        assertMessage("教室容量必须大于 0", () -> classroomService.createClassroom(zeroCapacity));

        ClassroomDTO longEquipment = dto("A101", "信息楼", "A101");
        longEquipment.setEquipment("x".repeat(Constants.CLASSROOM_EQUIPMENT_MAX_LENGTH + 1));
        assertMessage("设备说明过长", () -> classroomService.createClassroom(longEquipment));
    }

    @Test
    @DisplayName("createClassroom：校验通过 → 插入启用状态并失效缓存")
    void createClassroom_insertsEnabled() {
        classroomService.createClassroom(dto("A101", "信息楼", "A101"));

        verify(classroomMapper).insert(any(Classroom.class));
        verify(redisCache).invalidateBusinessCaches();
    }

    @Test
    @DisplayName("updateClassroom：ID 为空 / 教室不存在 / 字段非法 / 成功更新")
    void updateClassroom_branches() {
        ClassroomDTO noId = dto("A101", "信息楼", "A101");
        assertMessage("教室 ID 不能为空", () -> classroomService.updateClassroom(noId));

        when(classroomMapper.selectById(1L)).thenReturn(null);
        ClassroomDTO dto = dto("A101", "信息楼", "A101");
        dto.setId(1L);
        assertMessage("教室不存在", () -> classroomService.updateClassroom(dto));

        when(classroomMapper.selectById(1L)).thenReturn(room(1L));
        ClassroomDTO success = dto("A101", "信息楼", "A101");
        success.setId(1L);
        classroomService.updateClassroom(success);

        verify(classroomMapper).updateById(any(Classroom.class));
        verify(redisCache).invalidateBusinessCaches();
    }

    /* ==================== 删除 / 启停 ==================== */

    @Test
    @DisplayName("deleteClassroom：ID 为空 / 不存在 / 有预约记录时禁止删除")
    void deleteClassroom_rejectsInvalidCases() {
        assertMessage("教室 ID 不能为空", () -> classroomService.deleteClassroom(null));

        when(classroomMapper.selectOne(any())).thenReturn(null);
        assertMessage("教室不存在", () -> classroomService.deleteClassroom(1L));

        when(classroomMapper.selectOne(any())).thenReturn(room(1L));
        when(reservationMapper.selectCount(any())).thenReturn(2L);
        assertMessage("该教室存在预约记录，禁止删除", () -> classroomService.deleteClassroom(1L));
        verify(favoriteMapper, never()).delete(any());
    }

    @Test
    @DisplayName("deleteClassroom：无预约 → 清理收藏并删除教室，失效缓存")
    void deleteClassroom_cleansFavoritesAndRemoves() {
        when(classroomMapper.selectOne(any())).thenReturn(room(1L));
        when(reservationMapper.selectCount(any())).thenReturn(0L);

        classroomService.deleteClassroom(1L);

        verify(favoriteMapper).delete(any());
        verify(classroomMapper).deleteById(1L);
        verify(redisCache).invalidateBusinessCaches();
    }

    @Test
    @DisplayName("updateClassroomStatus：ID 为空 / 状态非法 / 不存在 / 状态一致幂等 / 正常更新")
    void updateClassroomStatus_branches() {
        assertMessage("教室 ID 不能为空", () -> classroomService.updateClassroomStatus(null, 1));
        assertMessage("状态参数不合法", () -> classroomService.updateClassroomStatus(1L, 9));

        when(classroomMapper.selectById(1L)).thenReturn(null);
        assertMessage("教室不存在", () -> classroomService.updateClassroomStatus(1L, 1));

        // 目标状态与当前状态一致 → 幂等返回，不写库
        Classroom enabled = room(1L);
        enabled.setStatus(Constants.CLASSROOM_STATUS_ENABLED);
        when(classroomMapper.selectById(1L)).thenReturn(enabled);
        classroomService.updateClassroomStatus(1L, Constants.CLASSROOM_STATUS_ENABLED);
        verify(classroomMapper, never()).updateById(any(Classroom.class));

        // 状态不同 → 执行更新
        classroomService.updateClassroomStatus(1L, Constants.CLASSROOM_STATUS_DISABLED);
        verify(classroomMapper).updateById(any(Classroom.class));
        verify(redisCache).invalidateBusinessCaches();
    }

    @Test
    @DisplayName("batchUpdateStatus：空列表 / 状态非法 / 正常批量更新返回条数")
    void batchUpdateStatus_branches() {
        assertMessage("教室 ID 列表不能为空", () -> classroomService.batchUpdateStatus(List.of(), 1));
        assertMessage("状态参数不合法", () -> classroomService.batchUpdateStatus(List.of(1L), 5));

        when(classroomMapper.update(any(), any())).thenReturn(2);
        int updated = classroomService.batchUpdateStatus(List.of(1L, 2L), Constants.CLASSROOM_STATUS_DISABLED);
        assertEquals(2, updated);
        verify(redisCache).invalidateBusinessCaches();
    }

    /* ==================== 学生端列表 ==================== */

    @Test
    @DisplayName("pageClassroomsForStudent：缓存命中直接返回，不查库")
    void pageClassroomsForStudent_cacheHit() {
        doReturn(new com.example.reservation.common.PageResult<ClassroomVO>(0L, List.of()))
                .when(redisCache).getObject(anyString(), any());

        var result = classroomService.pageClassroomsForStudent(1, 10, null, null, null, null);

        assertEquals(0L, result.getTotal());
        verify(classroomMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("pageClassroomsForStudent：缓存未命中 → 计算状态标签 / 今日剩余时段 / 指定日期占用")
    void pageClassroomsForStudent_computesDynamicFields() {
        Page<Classroom> page = new Page<>();
        page.setRecords(List.of(room(1L)));
        page.setTotal(1);
        when(classroomMapper.selectPage(any(), any())).thenReturn(page);

        // 当天已通过 10:00-11:00；指定日期 2026-11-20 另有 14:00-15:00
        when(reservationMapper.selectList(any())).thenReturn(
                List.of(approved(1L, LocalDate.now(), 10, 11)),
                List.of(approved(1L, LocalDate.of(2026, 11, 20), 14, 15)));

        var result = classroomService.pageClassroomsForStudent(1, 10, null, null, null, "2026-11-20");

        ClassroomVO vo = result.getRecords().get(0);
        // 14 个整点时段中被 10:00-11:00 占 1 个
        assertEquals(13, vo.getTodayRemainingSlots());
        assertEquals(1, vo.getOccupiedSlots().size());
        assertEquals("14:00", vo.getOccupiedSlots().get(0).getStartTime());
        // 列表路径共享缓存：不带用途与 mine
        assertNull(vo.getOccupiedSlots().get(0).getPurpose());
        assertNull(vo.getOccupiedSlots().get(0).getMine());
        verify(redisCache).setObject(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("pageClassroomsForStudent：空页不查预约表；无日期参数时只查当天")
    void pageClassroomsForStudent_emptyAndNoDate() {
        Page<Classroom> empty = new Page<>();
        when(classroomMapper.selectPage(any(), any())).thenReturn(empty);
        var result = classroomService.pageClassroomsForStudent(1, 10, null, null, null, null);
        assertTrue(result.getRecords().isEmpty());
        verify(reservationMapper, never()).selectList(any());

        Page<Classroom> one = new Page<>();
        one.setRecords(List.of(room(1L)));
        one.setTotal(1);
        when(classroomMapper.selectPage(any(), any())).thenReturn(one);
        when(reservationMapper.selectList(any())).thenReturn(List.of());
        classroomService.pageClassroomsForStudent(1, 10, null, null, null, null);
        verify(reservationMapper, times(1)).selectList(any());
    }

    /* ==================== 详情 ==================== */

    @Test
    @DisplayName("getClassroomDetail：ID 为空 / 教室不存在")
    void getClassroomDetail_rejectsInvalid() {
        assertMessage("教室 ID 不能为空", () -> classroomService.getClassroomDetail(null, null));
        when(classroomMapper.selectById(1L)).thenReturn(null);
        assertMessage("教室不存在", () -> classroomService.getClassroomDetail(1L, null));
    }

    @Test
    @DisplayName("getClassroomDetail：不传日期默认当天（复用同一份查询）；传日期则再查一次")
    void getClassroomDetail_dateDefaultAndExplicit() {
        when(classroomMapper.selectById(1L)).thenReturn(room(1L));
        when(reservationMapper.selectList(any())).thenReturn(List.of());

        ClassroomVO today = classroomService.getClassroomDetail(1L, null);
        assertEquals(Constants.STATUS_LABEL_FREE, today.getStatusLabel());
        verify(reservationMapper, times(1)).selectList(any());

        classroomService.getClassroomDetail(1L, "2026-11-20");
        verify(reservationMapper, times(3)).selectList(any());
    }

    @Test
    @DisplayName("getClassroomDetail：学生看不到他人预约用途，管理员可见")
    void getClassroomDetail_visibilityByRole() {
        when(classroomMapper.selectById(1L)).thenReturn(room(1L));
        Reservation others = approved(1L, LocalDate.now(), 14, 15);
        others.setUserId(2L);
        others.setPurpose("别人的用途");
        when(reservationMapper.selectList(any())).thenReturn(List.of(others));

        ClassroomVO studentView = classroomService.getClassroomDetail(1L, null);
        assertFalse(Boolean.TRUE.equals(studentView.getOccupiedSlots().get(0).getMine()));
        assertNull(studentView.getOccupiedSlots().get(0).getPurpose());

        UserContext.set(1L, "admin", Constants.ROLE_ADMIN);
        ClassroomVO adminView = classroomService.getClassroomDetail(1L, null);
        assertEquals("别人的用途", adminView.getOccupiedSlots().get(0).getPurpose());
    }

    @Test
    @DisplayName("状态标签：时段覆盖当前时刻 → 使用中（依赖当前时间窗口，过窄则跳过）")
    void statusLabel_inUse() {
        LocalTime now = LocalTime.now();
        Assumptions.assumeTrue(now.isAfter(LocalTime.of(9, 0)) && now.isBefore(LocalTime.of(21, 0)),
                "当前时刻不在 09:00-21:00，无法构造稳定「使用中」窗口");
        when(classroomMapper.selectById(1L)).thenReturn(room(1L));
        Reservation inUse = approved(1L, LocalDate.now(), 0, 0);
        inUse.setStartTime(now.minusMinutes(30));
        inUse.setEndTime(now.plusMinutes(30));
        when(reservationMapper.selectList(any())).thenReturn(List.of(inUse));

        ClassroomVO vo = classroomService.getClassroomDetail(1L, null);
        assertEquals(Constants.STATUS_LABEL_IN_USE, vo.getStatusLabel());
    }

    @Test
    @DisplayName("状态标签：当天预约全部结束 → 已结束（当前早于 10:00 时跳过）")
    void statusLabel_ended() {
        Assumptions.assumeTrue(LocalTime.now().isAfter(LocalTime.of(10, 0)),
                "当前早于 10:00，无法构造「已结束」窗口");
        when(classroomMapper.selectById(1L)).thenReturn(room(1L));
        when(reservationMapper.selectList(any()))
                .thenReturn(List.of(approved(1L, LocalDate.now(), 8, 10)));

        ClassroomVO vo = classroomService.getClassroomDetail(1L, null);
        assertEquals(Constants.STATUS_LABEL_ENDED, vo.getStatusLabel());
    }

    /* ==================== 工具方法 ==================== */

    private static Classroom room(Long id) {
        Classroom c = new Classroom();
        c.setId(id);
        c.setName("A101");
        c.setBuilding("信息楼");
        c.setRoomNo("A1");
        c.setType(Constants.CLASSROOM_TYPE_NORMAL);
        c.setCapacity(60);
        c.setStatus(Constants.CLASSROOM_STATUS_ENABLED);
        return c;
    }

    private static Reservation approved(Long classroomId, LocalDate date, int startHour, int endHour) {
        Reservation r = new Reservation();
        r.setId(99L);
        r.setClassroomId(classroomId);
        r.setUserId(1L);
        r.setReserveDate(date);
        r.setStartTime(LocalTime.of(startHour, 0));
        r.setEndTime(LocalTime.of(endHour, 0));
        r.setStatus(Constants.RES_STATUS_APPROVED);
        return r;
    }

    private static ClassroomDTO dto(String name, String building, String roomNo) {
        ClassroomDTO dto = new ClassroomDTO();
        dto.setName(name);
        dto.setBuilding(building);
        dto.setRoomNo(roomNo);
        dto.setType(Constants.CLASSROOM_TYPE_NORMAL);
        dto.setCapacity(60);
        dto.setEquipment("投影仪");
        dto.setDescription("备注");
        return dto;
    }

    private static void assertMessage(String expectedPart, Runnable action) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        assertTrue(ex.getMessage().contains(expectedPart), "实际消息：" + ex.getMessage());
    }
}
