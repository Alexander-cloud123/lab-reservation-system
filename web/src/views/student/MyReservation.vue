<template>
  <div class="my-reservation-page page-container">
    <div class="page-head">
      <span class="page-title">我的预约</span>
      <span class="page-tip">今日预约已置顶显示，待审核与已通过可取消</span>
    </div>
    <!-- 今天这一条：把今天的预约压成一条刻度尺，一眼看出时段占在哪 -->
    <div v-if="todaySlots.length" class="today-band surface reveal">
      <span class="today-band-label">今天</span>
      <TimeRuler class="today-band-ruler" compact :slots="todaySlots" />
    </div>

    <div
      ref="listRegionRef"
      class="reserve-card surface"
      role="region"
      aria-label="我的预约列表"
      tabindex="-1"
    >
      <!-- 状态分类查看 -->
      <el-tabs v-model="activeStatus" @tab-change="handleTabChange">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane label="待审核" name="0" />
        <el-tab-pane label="已通过" name="1" />
        <el-tab-pane label="已驳回" name="2" />
        <el-tab-pane label="已取消" name="3" />
      </el-tabs>

      <el-table
        v-loading="loading"
        :data="records"
        :row-class-name="rowClassName"
        empty-text=""
      >
        <el-table-column prop="classroomName" label="教室" min-width="160">
          <template #default="{ row }">
            <div class="room-cell">
              <span class="room-name">{{ row.classroomName }}</span>
              <span class="room-no">{{ row.building }}-{{ row.roomNo }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="reserveDate" label="预约日期" width="130">
          <template #default="{ row }">
            <span>{{ row.reserveDate }}</span>
            <span v-if="isToday(row.reserveDate)" class="today-tag">今日</span>
          </template>
        </el-table-column>
        <el-table-column label="时段" width="120">
          <template #default="{ row }">{{ row.startTime }}-{{ row.endTime }}</template>
        </el-table-column>
        <el-table-column prop="purpose" label="用途" min-width="160" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <span class="status-cell" :class="statusDotClass(row.status)">{{ statusText(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="auditRemark" label="审核备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.auditRemark || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 0 || row.status === 1"
              link
              type="danger"
              :loading="cancelingId === row.id"
              :disabled="cancelingId !== null"
              @click="handleCancel(row)"
            >
              取消
            </el-button>
            <span v-else class="no-action">-</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 空状态 -->
      <el-empty v-if="!loading && !records.length" description="暂无预约记录，去挑一间教室吧">
        <el-button type="primary" @click="goList">去教室列表</el-button>
      </el-empty>

      <!-- 分页 -->
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="loadData"
          @current-change="loadData"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import TimeRuler from '@/components/common/TimeRuler.vue'
import { getMyReservations, cancelReservation } from '@/api/reservation'
import { statusText } from '@/utils/dict'

const router = useRouter()
const loading = ref(false)
const records = ref([])
const total = ref(0)
const activeStatus = ref('all')
/** 列表区域（取消成功后的焦点落点，避免触发按钮被移除后焦点悬空到 body） */
const listRegionRef = ref()
/** 正在离场的行（取消成功后先淡出再刷新，避免行瞬间消失） */
const leavingIds = reactive(new Set())

/** 正在取消的预约 ID（R6：请求期间禁用所有取消按钮，防止快速连续点击重复提交） */
const cancelingId = ref(null)

/** 今天（今日预约置顶判定基准） */
const today = dayjs().format('YYYY-MM-DD')

/** 查询条件（含分页） */
const query = reactive({
  page: 1,
  size: 10,
  status: undefined
})

/** 加载我的预约（今日优先由后端在分页前全局排序，前端保持服务端页内顺序） */
async function loadData() {
  loading.value = true
  try {
    const params = {
      page: query.page,
      size: query.size
    }
    if (query.status !== undefined) {
      params.status = query.status
    }
    const res = await getMyReservations(params)
    records.value = res.data.records
    total.value = res.data.total
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    loading.value = false
  }
}

/** 今天仍在生效的预约：渲染成顶部刻度尺（已取消的不画） */
const todaySlots = computed(() =>
  (records.value || [])
    .filter((row) => row.reserveDate === today && row.status !== 3)
    .map((row) => ({ startTime: row.startTime, endTime: row.endTime, mine: true, purpose: row.purpose }))
)

/** 行样式：今日预约突出显示；正在取消的行先淡出再刷新 */
function rowClassName({ row }) {
  const classes = []
  if (row.reserveDate === today) {
    classes.push('today-row')
  }
  if (leavingIds.has(row.id)) {
    classes.push('is-leaving')
  }
  return classes.join(' ')
}

/** 判断是否为今天 */
function isToday(date) {
  return date === today
}

/** 状态圆点色（0 待审核 / 1 已通过 / 2 已驳回 / 3 已取消） */
function statusDotClass(status) {
  return { 0: 'is-pending', 1: 'is-approved', 2: 'is-rejected', 3: 'is-canceled' }[status] || 'is-canceled'
}

/** 切换状态标签页 */
function handleTabChange(name) {
  query.status = name === 'all' ? undefined : Number(name)
  query.page = 1
  loadData()
}

/** 取消预约（二次确认 + 请求期间防重复点击） */
async function handleCancel(row) {
  try {
    await ElMessageBox.confirm(
      `确定要取消「${row.classroomName}」${row.reserveDate} ${row.startTime}-${row.endTime} 的预约吗？取消后不可恢复。`,
      '取消预约确认',
      { confirmButtonText: '确定取消', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    // 用户点了「再想想」
    return
  }
  if (cancelingId.value !== null) {
    return
  }
  cancelingId.value = row.id
  try {
    await cancelReservation(row.id)
    ElMessage.success('取消成功')
    // 先让该行淡出 180ms，再刷新列表，避免行瞬间消失
    leavingIds.add(row.id)
    await new Promise((resolve) => setTimeout(resolve, 180))
    leavingIds.delete(row.id)
    await loadData()
    // 触发按钮随状态更新被移除，Element Plus 焦点复原目标已脱离 DOM（否则焦点落到 body）。
    // 无障碍验收 C4：把焦点交给列表区域（role=region，tabindex=-1），键盘用户可在列表内继续 Tab。
    await nextTick()
    const region = listRegionRef.value && listRegionRef.value.$el ? listRegionRef.value.$el : listRegionRef.value
    if (region && typeof region.focus === 'function') {
      region.focus()
    }
  } catch {
    // 接口报错（统一提示：如已过审核时间不可取消等）
  } finally {
    cancelingId.value = null
  }
}

/** 去教室列表 */
function goList() {
  router.push('/student/home')
}

onMounted(loadData)
</script>

<style scoped>
.today-band {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-md) var(--space-lg);
  margin-bottom: var(--space-lg);
}

.today-band-label {
  flex: none;
  font-size: var(--text-sm);
  font-weight: 600;
  color: var(--ink);
}

.today-band-ruler {
  flex: 1;
  min-width: 0;
}

.my-reservation-page {
  max-width: 1200px;
  margin: 0 auto;
}

.reserve-card {
  padding: 0;
}

.reserve-card :deep(.el-tabs__header) {
  margin: 0;
  padding: var(--space-sm) var(--space-md) 0;
}

.reserve-card :deep(.el-tabs__item) {
  height: 44px;
  font-size: var(--text-sm);
}

.reserve-card :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
  background-color: var(--line);
}

.reserve-card :deep(.el-table) {
  padding: 0 var(--space-md);
}

.room-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.4;
}

.room-name {
  color: var(--ink);
  font-weight: 600;
}

.room-no {
  color: var(--ink-soft);
  font-size: var(--text-xs);
}

.no-action {
  color: var(--ink-soft);
}

/* 今日预约置顶：左侧品牌竖线 + 极浅底，不做整行填色 */
.today-row td {
  background: var(--page) !important;
}

.today-row td:first-child {
  box-shadow: inset 2px 0 0 var(--brand);
}

.today-tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  margin-left: var(--space-sm);
  padding: 0 8px;
  border-radius: var(--radius-pill);
  background: var(--attention);
  color: var(--surface);
  font-size: var(--text-xs);
  font-weight: 500;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  padding: var(--space-md);
}

/* ---------- 窄屏适配（P3-8：<480px 分页换行，避免横向溢出） ---------- */
@media (max-width: 480px) {
  .pagination-wrap {
    justify-content: center;
  }

  .pagination-wrap :deep(.el-pagination) {
    flex-wrap: wrap;
    justify-content: center;
    row-gap: 6px;
  }
}
</style>