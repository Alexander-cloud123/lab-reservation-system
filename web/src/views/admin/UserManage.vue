<template>
  <div class="user-manage-page page-container">
    <!-- 页头：标题 + 说明 + 右侧提示 -->
    <div class="page-head">
      <div>
        <h1 class="page-title">用户管理</h1>
        <p class="page-sub">账号查询、启用/禁用与密码重置</p>
      </div>
      <p class="page-tip">当前登录的管理员账号不可禁用，也不可重置密码</p>
    </div>

    <!-- 筛选条：一张白面，控件同高 -->
    <div class="search-card">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model="query.keyword"
            placeholder="账号 / 姓名 / 学号"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item v-if="filtersExpanded" label="角色">
          <el-select v-model="query.role" placeholder="全部" clearable style="width: 132px">
            <el-option label="学生" :value="0" />
            <el-option label="管理员" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="filtersExpanded" label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 132px">
            <el-option label="正常" :value="1" />
            <el-option label="禁用" :value="0" />
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

    <!-- 用户表格：白面表格容器 -->
    <div class="table-band">
      <div class="section-title">
        <span>账号列表</span>
        <span class="section-note">共 {{ hasLoaded ? total : '—' }} 个账号</span>
      </div>

      <div v-if="loadError && records.length" class="list-refresh-error" role="status">
        <span>刷新失败，已保留当前账号列表。</span>
        <el-button link type="primary" @click="loadData">重试</el-button>
      </div>

      <el-table v-loading="loading" :data="records">
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="username" label="账号" min-width="100" />
        <el-table-column prop="name" label="姓名" min-width="88" />
        <el-table-column prop="studentNo" label="学号" min-width="112">
          <template #default="{ row }">{{ row.studentNo || '-' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" min-width="112">
          <template #default="{ row }">{{ row.phone || '-' }}</template>
        </el-table-column>
        <el-table-column prop="role" label="角色" width="80">
          <template #default="{ row }">
            <span class="status-cell" :class="row.role === 1 ? 'is-brand' : ''">
              {{ row.role === 1 ? '管理员' : '学生' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="88">
          <template #default="{ row }">
            <span class="status-cell" :class="row.status === 1 ? 'is-approved' : 'is-rejected'">
              {{ row.status === 1 ? '正常' : '禁用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <template v-if="row.id === currentUserId">
              <el-tooltip content="不能操作当前登录的管理员账号" placement="top">
                <el-button link type="primary" disabled>禁用/启用</el-button>
              </el-tooltip>
              <el-button link type="primary" disabled>重置密码</el-button>
            </template>
            <template v-else>
              <el-button
                link
                :type="row.status === 1 ? 'danger' : 'primary'"
                @click="handleToggleStatus(row)"
              >
                {{ row.status === 1 ? '禁用' : '启用' }}
              </el-button>
              <el-button link type="primary" @click="handleResetPassword(row)">重置密码</el-button>
            </template>
          </template>
        </el-table-column>

        <template #empty>
          <div v-if="loadError" class="list-error" role="alert">
            <span>账号列表加载失败，请重试。</span>
            <el-button link type="primary" @click="loadData">重试</el-button>
          </div>
          <div v-else-if="hasLoaded" class="empty-slate">
            没有找到符合条件的用户<el-button link type="primary" class="empty-action" @click="handleReset">重置筛选</el-button>
          </div>
        </template>
      </el-table>

      <!-- 分页 -->
      <div v-if="hasLoaded" class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
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
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { compactQuery, queryEnum, queryInteger, queryText, sameQuery } from '@/utils/routeQuery'
import { Search, Refresh, ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { pageUsers, updateUserStatus, resetUserPassword } from '@/api/user'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const currentUserId = userStore.userInfo ? userStore.userInfo.id : null

const loading = ref(false)
const loadError = ref(false)
const hasLoaded = ref(false)
const records = ref([])
const total = ref(0)

/** 查询条件（含分页） */
const query = reactive({
  page: 1,
  size: 10,
  keyword: '',
  role: null,
  status: null
})

/** 渐进式披露：次级筛选折叠在「更多筛选」后面 */
const filtersExpanded = ref(false)

const route = useRoute()
const router = useRouter()
const PAGE_SIZES = [10, 20, 50]
const DEFAULT_SIZE = 10
let listSeq = 0
let activeDataKey = ''

function parseRouteQuery(raw) {
  const role = queryEnum(raw.role, ['0', '1'])
  const status = queryEnum(raw.status, ['all', '0', '1'])
  return {
    page: queryInteger(raw.page, { fallback: 1, min: 1 }),
    size: queryInteger(raw.size, { fallback: DEFAULT_SIZE, min: 1, allowed: PAGE_SIZES }),
    keyword: queryText(raw.q),
    role: role === '' ? null : Number(role),
    status: status === '' || status === 'all' ? null : Number(status),
    expanded: queryEnum(raw.expanded, ['1']) === '1'
  }
}

function toRouteQuery(state) {
  return compactQuery({
    page: state.page === 1 ? '' : state.page,
    size: state.size === DEFAULT_SIZE ? '' : state.size,
    q: queryText(state.keyword),
    role: state.role === null ? '' : state.role,
    status: state.status === null ? '' : state.status,
    expanded: state.expanded ? '1' : ''
  })
}

function dataKey(state) {
  return JSON.stringify([state.page, state.size, state.keyword, state.role, state.status])
}

function applyRouteState(state) {
  query.page = state.page
  query.size = state.size
  query.keyword = state.keyword
  query.role = state.role
  query.status = state.status
  filtersExpanded.value = state.expanded
}

function pushRoute() {
  const target = toRouteQuery({ ...query, expanded: filtersExpanded.value })
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

/** 折叠后仅摘要展示高级条件；一直可见的关键词不在此重复。 */
const activeAdvancedFilters = computed(() => {
  const filters = []
  if (query.role !== null && query.role !== '') {
    filters.push({ key: 'role', label: '角色', value: query.role === 1 ? '管理员' : '学生' })
  }
  if (query.status !== null && query.status !== '') {
    filters.push({ key: 'status', label: '状态', value: query.status === 1 ? '正常' : '禁用' })
  }
  return filters
})

/** 清除单个高级条件并回到第一页重新查询。 */
function clearAdvancedFilter(key) {
  query[key] = null
  query.page = 1
  pushRoute()
}

/** 加载用户列表 */
async function loadData() {
  const seq = ++listSeq
  loading.value = true
  loadError.value = false
  const params = {
    page: query.page,
    size: query.size,
    keyword: query.keyword || undefined,
    role: query.role === null || query.role === '' ? undefined : query.role,
    status: query.status === null || query.status === '' ? undefined : query.status
  }
  try {
    const res = await pageUsers(params)
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

/** 修改每页条数时先回到第一页，避免原高页码越过新分页边界。 */
function handleSizeChange() {
  query.page = 1
  pushRoute()
}

/** 重置筛选条件 */
function handleReset() {
  query.keyword = ''
  query.role = null
  query.status = null
  query.page = 1
  if (!pushRoute()) loadData()
}

/** 启用/禁用（二次确认） */
async function handleToggleStatus(row) {
  const action = row.status === 1 ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确定要${action}用户「${row.name}（${row.username}）」吗？${action === '禁用' ? '禁用后该用户将无法登录。' : ''}`,
      `${action}确认`,
      { confirmButtonText: `确定${action}`, cancelButtonText: '取消', type: 'warning' }
    )
    await updateUserStatus(row.id, row.status === 1 ? 0 : 1)
    ElMessage.success(`${action}成功`)
    loadData()
  } catch {
    // 用户取消或接口报错（统一提示）
  }
}

/** 重置密码（二次确认） */
async function handleResetPassword(row) {
  try {
    await ElMessageBox.confirm(
      `确定要将用户「${row.name}（${row.username}）」的密码重置为默认密码 123456 吗？`,
      '重置密码确认',
      { confirmButtonText: '确定重置', cancelButtonText: '取消', type: 'warning' }
    )
    await resetUserPassword(row.id)
    ElMessage.success('密码已重置为默认密码 123456')
  } catch {
    // 用户取消或接口报错（统一提示）
  }
}

/** 时间格式化 */
function formatTime(time) {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

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
