package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.reservation.common.Constants;
import com.example.reservation.common.UserContext;
import com.example.reservation.config.RedisCache;
import com.example.reservation.config.RedisProperties;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.UserFavorite;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.UserFavoriteMapper;
import com.example.reservation.vo.ClassroomVO;
import com.example.reservation.vo.OccupiedSlotVO;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 教室删除竞态（R2）与占用时段用途裁剪（R5）单元测试（纯 Mockito，不依赖数据库）。
 * 断言策略与 FavoriteServiceImplTest 一致：方法级断言"调用了哪个 mapper 方法"，
 * 锁住修复点（存在性校验改为加锁读；详情路径按身份裁剪用途）。
 *
 * @author reservation-team
 */
@ExtendWith(MockitoExtension.class)
class ClassroomServiceImplTest {

    @Mock
    private ClassroomMapper classroomMapper;

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private UserFavoriteMapper favoriteMapper;

    @Mock
    private RedisCache redisCache;

    @Mock
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
        // 模拟登录态（学生 1 号）
        UserContext.set(1L, "student01", Constants.ROLE_STUDENT);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /* ===== R2：删除教室竞态（存在性校验改为加锁读，先于预约计数） ===== */

    @Test
    @DisplayName("R2：删除教室的存在性校验已改为加锁读，且先于预约计数")
    void deleteClassroom_locksClassroomBeforeCounting() {
        when(classroomMapper.selectOne(any())).thenReturn(new Classroom());
        when(reservationMapper.selectCount(any())).thenReturn(0L);

        classroomService.deleteClassroom(1L);

        // 修复本质：存在性校验从 selectById（普通读）改为 selectOne(...FOR UPDATE)（加锁读），
        // 与 createReservation 争抢同一把教室行锁；加锁读先于 selectCount
        verify(classroomMapper).selectOne(any());
        verify(classroomMapper, never()).selectById(any());
        InOrder inOrder = inOrder(classroomMapper, reservationMapper);
        inOrder.verify(classroomMapper).selectOne(any());
        inOrder.verify(reservationMapper).selectCount(any());
        verify(classroomMapper).deleteById(1L);
    }

    /* ===== R5：详情路径按身份裁剪用途（他人不返回、本人正常返回） ===== */

    @Test
    @DisplayName("R5：详情路径按身份裁剪——他人预约用途为 null、本人用途正常返回")
    void getClassroomDetail_prunesPurposeByOwner() {
        Classroom room = new Classroom();
        room.setId(1L);
        room.setName("A101");
        when(classroomMapper.selectById(1L)).thenReturn(room);
        Reservation others = reservation(2L, LocalTime.of(14, 0), LocalTime.of(16, 0), 2L, "他人用途");
        Reservation mine = reservation(1L, LocalTime.of(9, 0), LocalTime.of(11, 0), 1L, "我的用途");
        // 方法内先查 todayApproved（今天）、再查 dayApproved（指定日期），连续 stubbing
        when(reservationMapper.selectList(any())).thenReturn(List.of()).thenReturn(List.of(others, mine));

        ClassroomVO vo = classroomService.getClassroomDetail(1L, "2026-09-20");

        List<OccupiedSlotVO> slots = vo.getOccupiedSlots();
        assertEquals(2, slots.size());
        OccupiedSlotVO otherSlot = slots.stream()
                .filter(s -> s.getStartTime().equals("14:00")).findFirst().orElseThrow();
        assertNull(otherSlot.getPurpose());
        assertEquals(Boolean.FALSE, otherSlot.getMine());
        OccupiedSlotVO mySlot = slots.stream()
                .filter(s -> s.getStartTime().equals("09:00")).findFirst().orElseThrow();
        assertEquals("我的用途", mySlot.getPurpose());
        assertEquals(Boolean.TRUE, mySlot.getMine());
    }

    /* ===== 测试数据构造 ===== */

    /** 预置一条已通过预约（指定用户与用途） */
    private Reservation reservation(long id, LocalTime start, LocalTime end, long userId, String purpose) {
        Reservation r = new Reservation();
        r.setId(id);
        r.setClassroomId(1L);
        r.setReserveDate(LocalDate.of(2026, 9, 20));
        r.setStartTime(start);
        r.setEndTime(end);
        r.setStatus(Constants.RES_STATUS_APPROVED);
        r.setUserId(userId);
        r.setPurpose(purpose);
        return r;
    }
}
