<template>
  <div class="record-page page-container">
    <!-- 页头：标题 + 说明 + 右侧导出 -->
    <div class="page-head">
      <div>
        <h1 class="page-title">预约记录</h1>
        <p class="page-sub">按日期、教室、用户与状态筛选全量预约记录</p>
      </div>
      <el-button type="primary" :icon="Download" :loading="exporting" @click="handleExport">
        导出 Excel
      </el-button>
    </div>

    <!-- 筛选条：一张白面，控件同高（需求文档 2.4 第 12 页） -->
    <div class="search-card">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="用户">
          <el-input
            v-model="query.keyword"
            placeholder="账号 / 姓名"
            clearable
            style="width: 160px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item v-if="filtersExpanded" label="日期范围">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item v-if="filtersExpanded" label="教室">
          <el-select
            v-model="query.classroomId"
            placeholder="全部教室"
            clearable
            filterable
            style="width: 200px"
          >
            <el-option
              v-for="room in classroomOptions"
              :key="room.id"
              :label="`${room.name}（${room.building}-${room.roomNo}）`"
              :value="room.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="filtersExpanded" label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 112px">
            <el-option label="待审核" :value="0" />
            <el-option label="已通过" :value="1" />
            <el-option label="已驳回" :value="2" />
            <el-option label="已取消" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          <el-button text type="primary" :icon="filtersExpanded ? ArrowUp : ArrowDown" :aria-expanded="filtersExpanded" @click="toggleFilters">
            {{ filtersExpanded ? '收起筛选' : '更多筛选' }}
          </el-button>
        </el-form-item>
      </el-form>
      <div v-if="!filtersExpanded && activeAdvancedFilters.length" class="filter-summary">
        <span>已生效 {{ activeAdvancedFilters.length }} 项高级筛选：</span>
        <button
          v-for="item in activeAdvancedFilters"
          :key="item.key"
          type="button"
          class="filter-summary-item"
          :aria-label="`清除${item.label}筛选`"
          @click="clearAdvancedFilter(item.key)"
        >
          {{ item.label }}：{{ item.value }}<span aria-hidden="true">×</span>
        </button>
      </div>
    </div>

    <!-- 记录表格：白面表格容器 -->
    <div class="table-band">
      <div class="section-title">
        <span>预约记录</span>
        <span class="section-note">筛选结果共 {{ hasLoaded ? total : '—' }} 条</span>
      </div>

      <div v-if="loadError && records.length" class="list-refresh-error" role="status">
        <span>刷新失败，已保留当前预约记录。</span>
        <el-button link type="primary" @click="loadData">重试</el-button>
      </div>

      <el-table v-loading="loading" :data="records">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column label="预约人" width="120">
          <template #default="{ row }">
            <div class="user-cell">
              <span>{{ row.userName }}</span>
              <span class="user-account">{{ row.userAccount }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="教室" min-width="160">
          <template #default="{ row }">
            <div class="room-cell">
              <span class="room-name">{{ row.classroomName }}</span>
              <span class="room-no">{{ row.building }}-{{ row.roomNo }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="reserveDate" label="预约日期" width="112" />
        <el-table-column label="时段" width="120">
          <template #default="{ row }">{{ row.startTime }}-{{ row.endTime }}</template>
        </el-table-column>
        <el-table-column prop="purpose" label="用途" min-width="140" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="88">
          <template #default="{ row }">
            <span class="status-cell" :class="statusCellClass(row.status)">{{ statusText(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="auditRemark" label="审核备注" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.auditRemark || '-' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>

        <template #empty>
          <div v-if="loadError" class="list-error" role="alert">
            <span>预约记录加载失败，请重试。</span>
            <el-button link type="primary" @click="loadData">重试</el-button>
          </div>
          <div v-else-if="hasLoaded" class="empty-slate">
            暂无符合条件的预约记录<el-button link type="primary" class="empty-action" @click="handleReset">重置筛选</el-button>
          </div>
        </template>
      </el-table>

      <!-- 分页 -->
      <div v-if="hasLoaded" class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { compactQuery, queryDate, queryEnum, queryInteger, queryText, sameQuery } from '@/utils/routeQuery'
import dayjs from 'dayjs'
import { Search, Refresh, Download, ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import { pageManageReservations, exportReservations } from '@/api/reservation'
import { pageClassrooms } from '@/api/classroom'
import { statusText, statusCellClass } from '@/utils/dict'

const loading = ref(false)
const loadError = ref(false)
const hasLoaded = ref(false)
const exporting = ref(false)
const records = ref([])
const total = ref(0)
const dateRange = ref(null)
const classroomOptions = ref([])

/** 查询条件（含分页）；classroomId 为 R6 新增「教室」筛选参数 */
const query = reactive({
  page: 1,
  size: 10,
  status: undefined,
  classroomId: undefined,
  keyword: ''
})

/** 渐进式披露：次级筛选折叠在「更多筛选」后面 */
const filtersExpanded = ref(false)

const route = useRoute()
const router = useRouter()
const PAGE_SIZES = [10, 20, 50, 100]
const DEFAULT_SIZE = 10
let listSeq = 0
let activeDataKey = ''

function parseRouteQuery(raw) {
  const status = queryEnum(raw.status, ['all', '0', '1', '2', '3'])
  const start = queryDate(raw.start)
  const end = queryDate(raw.end)
  const validRange = start && end && start <= end
  return {
    page: queryInteger(raw.page, { fallback: 1, min: 1 }),
    size: queryInteger(raw.size, { fallback: DEFAULT_SIZE, min: 1, allowed: PAGE_SIZES }),
    keyword: queryText(raw.q),
    status: status === '' || status === 'all' ? undefined : Number(status),
    start: validRange ? start : '',
    end: validRange ? end : '',
    classroomId: queryInteger(raw.classroomId, { fallback: null, min: 1 }),
    expanded: queryEnum(raw.expanded, ['1']) === '1'
  }
}

function toRouteQuery(state) {
  return compactQuery({
    page: state.page === 1 ? '' : state.page,
    size: state.size === DEFAULT_SIZE ? '' : state.size,
    q: queryText(state.keyword),
    status: state.status === undefined ? '' : state.status,
    start: state.start,
    end: state.end,
    classroomId: state.classroomId === null || state.classroomId === undefined ? '' : state.classroomId,
    expanded: state.expanded ? '1' : ''
  })
}

function dataKey(state) {
  return JSON.stringify([
    state.page,
    state.size,
    state.keyword,
    state.status,
    state.start,
    state.end,
    state.classroomId
  ])
}

function applyRouteState(state) {
  query.page = state.page
  query.size = state.size
  query.keyword = state.keyword
  query.status = state.status
  query.classroomId = state.classroomId
  dateRange.value = state.start && state.end ? [state.start, state.end] : null
  filtersExpanded.value = state.expanded
}

function pushRoute() {
  const start = dateRange.value && dateRange.value.length === 2 ? dateRange.value[0] : ''
  const end = dateRange.value && dateRange.value.length === 2 ? dateRange.value[1] : ''
  const target = toRouteQuery({ ...query, start, end, expanded: filtersExpanded.value })
  if (sameQuery(target, route.query)) return false
  router.push({ path: route.path, query: target })
  return true
}

function toggleFilters() {
  filtersExpanded.value = !filtersExpanded.value
  pushRoute()
}

watch(
  () => route.query,
  (raw) => {
    const state = parseRouteQuery(raw)
    applyRouteState(state)
    const normalized = toRouteQuery(state)
    if (Object.keys(normalized).length !== Object.keys(raw).length || !sameQuery(normalized, raw)) {
      router.replace({ path: route.path, query: normalized })
    }
    const nextDataKey = dataKey(state)
    if (nextDataKey !== activeDataKey) {
      activeDataKey = nextDataKey
      loadData()
    }
  },
  { immediate: true }
)

/** 折叠后仅摘要展示高级条件；一直可见的用户关键词不在此重复。 */
const activeAdvancedFilters = computed(() => {
  const filters = []
  if (dateRange.value && dateRange.value.length === 2) {
    filters.push({ key: 'dateRange', label: '日期范围', value: dateRange.value.join(' 至 ') })
  }
  if (query.classroomId !== undefined && query.classroomId !== null && query.classroomId !== '') {
    const room = classroomOptions.value.find((item) => item.id === query.classroomId)
    filters.push({
      key: 'classroomId',
      label: '教室',
      value: room ? `${room.name}（${room.building}-${room.roomNo}）` : `ID ${query.classroomId}`
    })
  }
  if (query.status !== undefined && query.status !== null && query.status !== '') {
    filters.push({ key: 'status', label: '状态', value: statusText(query.status) })
  }
  return filters
})

/** 清除单个高级条件并回到第一页重新查询。 */
function clearAdvancedFilter(key) {
  if (key === 'dateRange') {
    dateRange.value = null
  } else {
    query[key] = undefined
  }
  query.page = 1
  pushRoute()
}

/** 组装查询参数（空值剔除，避免向后端传多余参数） */
function buildParams() {
  const params = { page: query.page, size: query.size }
  if (query.status !== undefined && query.status !== null && query.status !== '')
    params.status = query.status
  if (query.classroomId !== undefined && query.classroomId !== null && query.classroomId !== '') {
    params.classroomId = query.classroomId
  }
  if (query.keyword) params.keyword = query.keyword.trim()
  if (dateRange.value && dateRange.value.length === 2) {
    params.startDate = dateRange.value[0]
    params.endDate = dateRange.value[1]
  }
  return params
}

/** 加载教室下拉选项（管理端接口，全量 12 间） */
async function loadClassrooms() {
  try {
    const res = await pageClassrooms({ page: 1, size: 500 })
    classroomOptions.value = res.data.records || []
  } catch {
    // 统一错误提示已由 request.js 处理
  }
}

/** 加载预约记录列表 */
async function loadData() {
  const seq = ++listSeq
  loading.value = true
  loadError.value = false
  const params = buildParams()
  try {
    const res = await pageManageReservations(params)
    if (seq !== listSeq) return
    records.value = res.data.records
    total.value = res.data.total
    hasLoaded.value = true
    loadError.value = false
  } catch {
    if (seq !== listSeq) return
    loadError.value = true
    // 统一错误提示已由 request.js 处理；旧数据保持不动。
  } finally {
    if (seq === listSeq) loading.value = false
  }
}

/** 查询与分页统一写入 URL，由路由监听器负责加载。 */
function handleSearch() {
  query.keyword = query.keyword.trim()
  query.page = 1
  if (!pushRoute()) loadData()
}

function handlePageChange() {
  pushRoute()
}

function handleSizeChange() {
  query.page = 1
  pushRoute()
}

function handleReset() {
  query.status = undefined
  query.classroomId = undefined
  query.keyword = ''
  dateRange.value = null
  query.page = 1
  if (!pushRoute()) loadData()
}

/** 导出 Excel：按当前筛选条件导出全部命中记录（xlsx 文件流） */
async function handleExport() {
  exporting.value = true
  try {
    const blob = await exportReservations(buildParams())
    // 触发浏览器下载（文件名带时间戳，避免同名覆盖）
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `预约记录_${dayjs().format('YYYYMMDDHHmmss')}.xlsx`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(url)
    ElMessage.success('导出成功，请查看下载文件')
  } catch {
    // 错误提示已由 request.js 统一处理（含 401/403/业务错误）
  } finally {
    exporting.value = false
  }
}

/** 创建时间展示（yyyy-MM-dd HH:mm:ss） */
function formatDateTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

onMounted(loadClassrooms)
</script>

<style scoped>
/* 筛选条：白面 + 24px 内边距，标签与控件同基线 */
.search-card {
  margin-bottom: var(--space-xl);
  padding: var(--space-lg) var(--space-xl);
}
.search-card :deep(.el-form-item) {
  margin-bottom: 0;
  margin-right: var(--space-xl);
}
.search-card :deep(.el-form-item__label) {
  font-size: var(--text-sm);
  color: var(--ink-soft);
  padding-right: var(--space-sm);
}
.filter-summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-top: var(--space-md);
  font-size: var(--text-sm);
  color: var(--ink-soft);
}
.filter-summary-item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: 2px 8px;
  border: 1px solid var(--line);
  border-radius: var(--radius-control);
  background: transparent;
  color: var(--ink);
  font: inherit;
  cursor: pointer;
}
.filter-summary-item span {
  color: var(--ink-soft);
}
.filter-summary-item:hover {
  border-color: var(--brand);
  color: var(--brand);
}

/* 表格容器：白面 + 24px 内边距 */
.table-band {
  padding: var(--space-xl);
}

/* 双行单元格：主信息 + 次要信息，靠字号与颜色分层 */
.user-cell,
.room-cell {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}
.user-account,
.room-no {
  font-size: var(--text-xs);
  color: var(--ink-soft);
}
.room-name {
  color: var(--ink);
  font-weight: 500;
}

/* 空态：覆盖组件内置空文案行高，统一走 empty-slate 原语 */
:deep(.el-table__empty-text) {
  width: 100%;
  line-height: 1.6;
}
.empty-slate,
.list-error {
  line-height: 1.6;
}
.list-refresh-error {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
  color: var(--warning);
  font-size: var(--text-sm);
}
.empty-action {
  margin-left: var(--space-xs);
  padding: 0;
  vertical-align: baseline;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-xl);
}

/* ---------- 第四轮（晨光玻璃）覆盖 ---------- */
.search-card,
.table-band {
  background: var(--glass-strong);
  -webkit-backdrop-filter: blur(16px) saturate(145%);
  backdrop-filter: blur(16px) saturate(145%);
  border: 1px solid var(--glass-line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-surface), var(--shadow-raised), var(--glass-sheen);
}
.room-no {
  font-family: var(--font-num);
  letter-spacing: 0.03em;
  color: var(--brand-deep);
}
.room-name {
  font-weight: 600;
  color: var(--ink);
}
.band-actions {
  background: var(--grad-soft);
  border-radius: var(--radius-control);
}
.ai-tag,
.reason-tag {
  border-radius: var(--radius-pill);
}
</style>
