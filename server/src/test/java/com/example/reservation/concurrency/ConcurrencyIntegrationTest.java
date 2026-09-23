package com.example.reservation.concurrency;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.UserContext;
import com.example.reservation.dto.AuditDTO;
import com.example.reservation.dto.ReservationDTO;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.SysUser;
import com.example.reservation.entity.UserFavorite;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.mapper.UserFavoriteMapper;
import com.example.reservation.service.FavoriteService;
import com.example.reservation.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发集成测试（把并发修复从「注释自证」固化为可回归的用例）。
 *
 * <p>为什么必须是集成测试而非 Mockito 单测：本组用例要验证的是数据库层的互斥语义——
 * 教室/用户行锁（SELECT ... FOR UPDATE）、条件更新的影响行数、以及 REPEATABLE READ 下
 * 「快照读 vs 当前读」的可见性差异。这些行为由 InnoDB 提供，Mock 掉 Mapper 后无从体现，
 * 因此这里走完整 Spring 上下文 + 真实 MySQL/Redis（与 AuthAndValidationSmokeTest 同一口径，
 * 运行前需保证 database/init_db.sql 已导入、MySQL/Redis 可用）。
 *
 * <p>覆盖的并发修复点：
 * <ul>
 *   <li>并发提交：已通过时段在并发提交下不被二次占用（提交路径「冲突检测+插入」原子）；</li>
 *   <li>并发审核：两条重叠待审核同时通过时恰一条成功（H2 加锁读闭合快照缺口 + M1 条件更新）；</li>
 *   <li>并发收藏：多路并发收藏不同教室时收藏数精确收敛到上限（M4/R1 sys_user 行锁串行化「计数+插入」）。</li>
 * </ul>
 *
 * <p>注意：本类<b>不能</b>标注 {@code @Transactional}——用例需要每个工作线程各自独立提交事务，
 * 若测试方法被纳入同一事务，行锁不会释放、并发语义完全失效。
 *
 * @author reservation-team
 */
@SpringBootTest
class ConcurrencyIntegrationTest {

    /**
     * 专用测试日期：远离种子数据（种子用 CURDATE()±7），
     * 既避免与演示数据互相干扰，也便于按日期一次性清理本类产生的全部预约。
     */
    private static final LocalDate TEST_DATE = LocalDate.of(2099, 12, 31);

    /** 并发收藏用例要求的最少启用教室数（种子数据 12 间） */
    private static final int REQUIRED_CLASSROOMS = 12;

    /** 种子管理员 ID（审核路径需管理员上下文） */
    private static final long ADMIN_ID = 1L;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private ReservationMapper reservationMapper;

    @Autowired
    private UserFavoriteMapper favoriteMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private ClassroomMapper classroomMapper;

    /** 本类专用的测试用户 ID（隔离收藏数据，避免污染种子用户的收藏） */
    private Long testUserId;

    /** 运行时取到的启用教室 ID（按 ID 升序），用于并发收藏用例 */
    private List<Long> enabledClassroomIds;

    /** 并发提交/审核用例使用的教室 */
    private Long classroomId;

    @BeforeEach
    void setUp() {
        List<Classroom> rooms = classroomMapper.selectList(new LambdaQueryWrapper<Classroom>()
                .eq(Classroom::getStatus, Constants.CLASSROOM_STATUS_ENABLED)
                .orderByAsc(Classroom::getId));
        assertTrue(rooms.size() >= REQUIRED_CLASSROOMS,
                "并发用例依赖至少 " + REQUIRED_CLASSROOMS + " 间启用教室，请先导入 database/init_db.sql");
        this.enabledClassroomIds = rooms.stream().map(Classroom::getId).toList();
        this.classroomId = enabledClassroomIds.get(0);

        // 专用测试用户：username 带纳秒后缀，避免多次运行/并行运行撞唯一索引
        SysUser user = new SysUser();
        user.setUsername("it_concurrency_" + System.nanoTime());
        user.setPassword("not-used-in-tests");   // 用例不登录，仅为满足 NOT NULL
        user.setName("并发测试用户");
        user.setRole(Constants.ROLE_STUDENT);
        user.setStatus(Constants.USER_STATUS_NORMAL);
        userMapper.insert(user);
        this.testUserId = user.getId();
    }

    @AfterEach
    void tearDown() {
        // 按专用日期清理预约、按测试用户清理收藏，最后删测试用户，保证演示数据零残留
        reservationMapper.delete(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getReserveDate, TEST_DATE));
        favoriteMapper.delete(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, testUserId));
        userMapper.deleteById(testUserId);
        UserContext.clear();
    }

    /* ==================== 用例 1：并发提交同一时段 ==================== */

    @Test
    @DisplayName("并发提交：已通过时段被 8 路并发预约时全部拒绝，不产生第二条占用")
    void concurrentCreate_rejectsAllWhenSlotAlreadyApproved() throws Exception {
        // 预置一条【已通过】预约 10:00-12:00
        insertReservation(testUserId, LocalTime.of(10, 0), LocalTime.of(12, 0), Constants.RES_STATUS_APPROVED);

        int threads = 8;
        // 8 个线程同时提交与之重叠的 11:00-13:00
        List<Throwable> failures = runConcurrently(threads, testUserId, Constants.ROLE_STUDENT,
                i -> reservationService.createReservation(dto("11:00", "13:00")));

        assertEquals(threads, failures.size(), "并发提交应全部被冲突拦截");
        for (Throwable t : failures) {
            assertInstanceOf(BusinessException.class, t, "应为业务异常（冲突拒绝）：" + t);
            assertTrue(t.getMessage().contains("冲突"), "拒绝原因应为时段冲突：" + t.getMessage());
        }
        // 核心不变量：该教室该日期仍只有最初那一条已通过记录，并发提交未产生任何新占用
        assertEquals(1L, countByClassAndDate(), "并发提交不得产生额外占用记录");
    }

    /* ==================== 用例 2：并发审核 ==================== */

    @Test
    @DisplayName("并发审核：两条重叠待审核同时通过，仅一条成功，杜绝双已通过")
    void concurrentAudit_onlyOneApproved() throws Exception {
        // 多轮执行以提高命中「快照读早于加锁」时序的概率（单轮时序不保证必然交错）
        int rounds = 10;
        for (int round = 1; round <= rounds; round++) {
            long idA = insertReservation(testUserId, LocalTime.of(10, 0), LocalTime.of(12, 0), Constants.RES_STATUS_PENDING);
            long idB = insertReservation(testUserId, LocalTime.of(11, 0), LocalTime.of(13, 0), Constants.RES_STATUS_PENDING);

            List<Throwable> failures = runConcurrently(2, ADMIN_ID, Constants.ROLE_ADMIN, i -> {
                AuditDTO dto = new AuditDTO();
                dto.setStatus(Constants.RES_STATUS_APPROVED);
                reservationService.auditReservation(i == 0 ? idA : idB, dto);
            });

            assertEquals(1, failures.size(), "第 " + round + " 轮：两条重叠待审核应恰有一条审核失败");
            assertInstanceOf(BusinessException.class, failures.get(0),
                    "第 " + round + " 轮：失败方应为业务异常（冲突拒绝）");
            assertTrue(failures.get(0).getMessage().contains("冲突"),
                    "第 " + round + " 轮：拒绝原因应为时段冲突：" + failures.get(0).getMessage());
            assertEquals(1L, countApprovedByClassAndDate(),
                    "第 " + round + " 轮：该教室该日期已通过记录必须唯一（不得双已通过）");

            // 清理本轮数据，避免上一轮的已通过记录影响下一轮复审
            deleteTestReservations();
        }
    }

    /* ==================== 用例 3：并发收藏到上限 ==================== */

    @Test
    @DisplayName("并发收藏：12 路并发收藏不同教室，收藏数精确收敛到上限 10")
    void concurrentFavorite_neverExceedsLimit() throws Exception {
        int threads = REQUIRED_CLASSROOMS;
        // 12 个线程同时收藏 12 间【不同】教室（测试用户初始收藏为 0）
        List<Throwable> failures = runConcurrently(threads, testUserId, Constants.ROLE_STUDENT,
                i -> favoriteService.toggleFavorite(enabledClassroomIds.get(i)));

        int expectedRejected = threads - Constants.FAVORITE_MAX_COUNT;
        assertEquals(expectedRejected, failures.size(),
                "并发收藏应有 " + expectedRejected + " 次被上限拒绝");
        for (Throwable t : failures) {
            assertInstanceOf(BusinessException.class, t, "应为业务异常（超上限拒绝）：" + t);
            assertTrue(t.getMessage().contains("已达上限"), "拒绝原因应为收藏已达上限：" + t.getMessage());
        }
        // 核心不变量：行锁串行化「计数+插入」后，收藏数精确等于上限，不会被并发突破
        assertEquals(Constants.FAVORITE_MAX_COUNT, countFavorites(),
                "并发收藏不得突破上限 " + Constants.FAVORITE_MAX_COUNT);
    }

    /* ==================== 并发执行骨架 ==================== */

    /**
     * 以「就绪闩 + 起跑闩」让 N 个线程同时冲入临界区，最大化竞态暴露概率。
     * 每个工作线程独立设置 {@link UserContext}（ThreadLocal，不设置则业务取不到登录态）。
     *
     * @param threads 并发线程数
     * @param userId  该批线程的登录用户 ID
     * @param role    该批线程的登录角色
     * @param task    线程任务，入参为线程序号（用于区分各自操作的数据）
     * @return 各线程抛出的异常（成功线程不计入），顺序不保证
     */
    private List<Throwable> runConcurrently(int threads, Long userId, Integer role, IntConsumer task)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                final int index = i;
                pool.submit(() -> {
                    UserContext.set(userId, "concurrency-test", role);
                    try {
                        ready.countDown();
                        start.await();
                        task.accept(index);
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        UserContext.clear();
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "并发线程未全部就绪");
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务超时未完成");
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }

    /* ==================== 测试数据与统计辅助 ==================== */

    /** 直接落库一条预约（绕开业务校验，用于构造已通过/待审核前置数据） */
    private long insertReservation(Long userId, LocalTime start, LocalTime end, int status) {
        Reservation r = new Reservation();
        r.setUserId(userId);
        r.setClassroomId(classroomId);
        r.setReserveDate(TEST_DATE);
        r.setStartTime(start);
        r.setEndTime(end);
        r.setPurpose("并发测试");
        r.setStatus(status);
        reservationMapper.insert(r);
        return r.getId();
    }

    /** 构造提交预约请求（时段位于 08:00-22:00 窗口内且不超单次时长上限） */
    private ReservationDTO dto(String start, String end) {
        ReservationDTO dto = new ReservationDTO();
        dto.setClassroomId(classroomId);
        dto.setReserveDate(TEST_DATE.toString());
        dto.setStartTime(start);
        dto.setEndTime(end);
        dto.setPurpose("并发测试");
        return dto;
    }

    /** 清理本类在专用日期上产生的全部预约 */
    private void deleteTestReservations() {
        reservationMapper.delete(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getReserveDate, TEST_DATE));
    }

    /** 该教室该日期的预约总数 */
    private long countByClassAndDate() {
        return reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getClassroomId, classroomId)
                .eq(Reservation::getReserveDate, TEST_DATE));
    }

    /** 该教室该日期【已通过】的预约数（双已通过即 >1） */
    private long countApprovedByClassAndDate() {
        return reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getClassroomId, classroomId)
                .eq(Reservation::getReserveDate, TEST_DATE)
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED));
    }

    /** 测试用户的收藏数 */
    private long countFavorites() {
        return favoriteMapper.selectCount(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, testUserId));
    }
}
