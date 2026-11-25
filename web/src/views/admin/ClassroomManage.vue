<template>
  <div class="classroom-manage-page page-container">
    <!-- 页头：标题 + 说明 + 新增入口 -->
    <div class="page-head">
      <div>
        <h1 class="page-title">教室管理</h1>
        <p class="page-sub">维护教室基础信息，控制教室可预约状态</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增教室</el-button>
    </div>

    <!-- 筛选条：一张白面，控件同高 -->
    <div class="search-card">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model="query.keyword"
            placeholder="教室名称 / 编号"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item v-if="filtersExpanded" label="楼栋">
          <el-select v-model="query.building" placeholder="全部" clearable style="width: 132px">
            <el-option v-for="b in buildingOptions" :key="b" :label="b" :value="b" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="filtersExpanded" label="类型">
          <el-select v-model="query.type" placeholder="全部" clearable style="width: 140px">
            <el-option v-for="t in ROOM_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="filtersExpanded" label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="可用" :value="1" />
            <el-option label="停用" :value="0" />
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

    <!-- 教室表格：白面表格容器 -->
    <div class="table-band">
      <div class="section-title">
        <span class="title-left">
          教室列表
          <span class="section-note">共 {{ hasLoaded ? total : '—' }} 间，已选 {{ selectedIds.length }} 间</span>
        </span>
        <span class="band-actions">
          <el-button
            type="primary"
            plain
            :icon="CircleCheck"
            :disabled="!selectedIds.length"
            @click="handleBatchStatus(1)"
          >
            批量启用
          </el-button>
          <el-button
            type="danger"
            plain
            :icon="CircleClose"
            :disabled="!selectedIds.length"
            @click="handleBatchStatus(0)"
          >
            批量停用
          </el-button>
        </span>
      </div>

      <div v-if="loadError && records.length" class="list-refresh-error" role="status">
        <span>刷新失败，已保留当前教室列表。</span>
        <el-button link type="primary" @click="loadData">重试</el-button>
      </div>

      <el-table v-loading="loading" :data="records" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="48" />
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="name" label="教室名称" min-width="140" />
        <el-table-column prop="building" label="楼栋" width="88" />
        <el-table-column prop="roomNo" label="编号" width="88" />
        <el-table-column prop="type" label="类型" width="96">
          <template #default="{ row }">
            <span class="status-cell">{{ typeText(row.type) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="capacity" label="容量" width="80" />
        <el-table-column prop="equipment" label="设备说明" min-width="148" show-overflow-tooltip>
          <template #default="{ row }">{{ row.equipment || '-' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="84">
          <template #default="{ row }">
            <span class="status-cell" :class="row.status === 1 ? 'is-approved' : 'is-rejected'">
              {{ row.status === 1 ? '可用' : '停用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button
              link
              :type="row.status === 1 ? 'danger' : 'primary'"
              @click="handleToggleStatus(row)"
            >
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div v-if="loadError" class="list-error" role="alert">
            <span>教室列表加载失败，请重试。</span>
            <el-button link type="primary" @click="loadData">重试</el-button>
          </div>
          <div v-else-if="hasLoaded" class="empty-slate">
            没有找到符合条件的教室<el-button link type="primary" class="empty-action" @click="handleReset">重置筛选</el-button>
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

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="form.id ? '编辑教室' : '新增教室'"
      width="520px"
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="88px">
        <el-form-item label="教室名称" prop="name">
          <el-input v-model="form.name" placeholder="如：A101多媒体教室" maxlength="50" />
        </el-form-item>
        <el-form-item label="所属楼栋" prop="building">
          <el-input v-model="form.building" placeholder="如：信息楼" maxlength="30" />
        </el-form-item>
        <el-form-item label="教室编号" prop="roomNo">
          <el-input v-model="form.roomNo" placeholder="如：A101" maxlength="20" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" placeholder="请选择类型" style="width: 100%">
            <el-option v-for="t in ROOM_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="容纳人数" prop="capacity">
          <el-input-number v-model="form.capacity" :min="1" :max="10000" style="width: 100%" />
        </el-form-item>
        <el-form-item label="设备说明" prop="equipment">
          <el-input
            v-model="form.equipment"
            type="textarea"
            :rows="2"
            maxlength="255"
            placeholder="选填"
          />
        </el-form-item>
        <el-form-item label="备注描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            maxlength="255"
            placeholder="选填"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { compactQuery, queryEnum, queryInteger, queryText, sameQuery } from '@/utils/routeQuery'
import { Search, Refresh, Plus, CircleCheck, CircleClose, ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import {
  pageClassrooms,
  createClassroom,
  updateClassroom,
  deleteClassroom,
  updateClassroomStatus,
  batchClassroomStatus
} from '@/api/classroom'
import { ROOM_TYPES, typeText } from '@/utils/dict'

const loading = ref(false)
const loadError = ref(false)
const hasLoaded = ref(false)
const records = ref([])
const total = ref(0)
const selectedIds = ref([])
const buildingOptions = ref([])

/** 查询条件（含分页） */
const query = reactive({
  page: 1,
  size: 10,
  keyword: '',
  building: null,
  type: null,
  status: null
})

/** 渐进式披露：次级筛选折叠在「更多筛选」后面 */
const filtersExpanded = ref(false)

const route = useRoute()
const router = useRouter()
const PAGE_SIZES = [10, 20, 50]
const DEFAULT_SIZE = 10
const BUILDING_PAGE_SIZE = 500
let listSeq = 0
let buildingSeq = 0
let activeDataKey = ''

function parseRouteQuery(raw) {
  const type = queryEnum(raw.type, ROOM_TYPES.map((item) => item.value))
  const status = queryEnum(raw.status, ['all', '0', '1'])
  return {
    page: queryInteger(raw.page, { fallback: 1, min: 1 }),
    size: queryInteger(raw.size, { fallback: DEFAULT_SIZE, min: 1, allowed: PAGE_SIZES }),
    keyword: queryText(raw.q),
    building: queryText(raw.building) || null,
    type: type === '' ? null : Number(type),
    status: status === '' || status === 'all' ? null : Number(status),
    expanded: queryEnum(raw.expanded, ['1']) === '1'
  }
}

function toRouteQuery(state) {
  return compactQuery({
    page: state.page === 1 ? '' : state.page,
    size: state.size === DEFAULT_SIZE ? '' : state.size,
    q: queryText(state.keyword),
    building: queryText(state.building),
    type: state.type === null ? '' : state.type,
    status: state.status === null ? '' : state.status,
    expanded: state.expanded ? '1' : ''
  })
}

function dataKey(state) {
  return JSON.stringify([state.page, state.size, state.keyword, state.building, state.type, state.status])
}

function applyRouteState(state) {
  query.page = state.page
  query.size = state.size
  query.keyword = state.keyword
  query.building = state.building
  query.type = state.type
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
  if (query.building) {
    filters.push({ key: 'building', label: '楼栋', value: query.building })
  }
  if (query.type !== null && query.type !== '') {
    filters.push({ key: 'type', label: '类型', value: typeText(query.type) })
  }
  if (query.status !== null && query.status !== '') {
    filters.push({ key: 'status', label: '状态', value: query.status === 1 ? '可用' : '停用' })
  }
  return filters
})

/** 清除单个高级条件并回到第一页重新查询。 */
function clearAdvancedFilter(key) {
  query[key] = null
  query.page = 1
  pushRoute()
}

/** 新增/编辑弹窗状态 */
const dialogVisible = ref(false)
const submitting = ref(false)
const formRef = ref(null)
const emptyForm = {
  id: null,
  name: '',
  building: '',
  roomNo: '',
  type: null,
  capacity: 1,
  equipment: '',
  description: ''
}
const form = reactive({ ...emptyForm })

/** 表单校验规则（与后端一致：必填 + 容量>0） */
const formRules = {
  name: [{ required: true, message: '请输入教室名称', trigger: 'blur' }],
  building: [{ required: true, message: '请输入所属楼栋', trigger: 'blur' }],
  roomNo: [{ required: true, message: '请输入教室编号', trigger: 'blur' }],
  type: [{ required: true, message: '请选择教室类型', trigger: 'change' }],
  capacity: [{ required: true, message: '请输入容纳人数', trigger: 'blur' }]
}

/** 加载教室列表 */
async function loadData() {
  const seq = ++listSeq
  loading.value = true
  loadError.value = false
  const params = {
    page: query.page,
    size: query.size,
    keyword: query.keyword || undefined,
    building: query.building || undefined,
    type: query.type === null || query.type === '' ? undefined : query.type,
    status: query.status === null || query.status === '' ? undefined : query.status
  }
  try {
    const res = await pageClassrooms(params)
    if (seq !== listSeq) return
    records.value = res.data.records
    total.value = res.data.total
    hasLoaded.value = true
    loadError.value = false
    mergeLoadedBuildings()
  } catch {
    if (seq !== listSeq) return
    loadError.value = true
    // 统一错误提示已由 request.js 处理；旧数据保持不动。
  } finally {
    if (seq === listSeq) loading.value = false
  }
}

/** 在全量字典上合并当前页楼栋；字典失败时也能回退，且能反映其他端新增的数据。 */
function mergeLoadedBuildings() {
  const values = [...buildingOptions.value, ...records.value.map((room) => room.building)]
  buildingOptions.value = [...new Set(values.filter(Boolean))].sort((left, right) =>
    String(left).localeCompare(String(right), 'zh-CN')
  )
}

/** 独立读取全量教室，按 total 取完所有页后生成楼栋字典。 */
async function loadBuildingOptions() {
  const seq = ++buildingSeq
  try {
    const first = await pageClassrooms({ page: 1, size: BUILDING_PAGE_SIZE })
    if (seq !== buildingSeq) return
    const rows = [...(first.data.records || [])]
    const reportedTotal = Number(first.data.total)
    const pageCount = Math.ceil((Number.isFinite(reportedTotal) ? reportedTotal : rows.length) / BUILDING_PAGE_SIZE)
    for (let page = 2; page <= pageCount; page += 1) {
      const res = await pageClassrooms({ page, size: BUILDING_PAGE_SIZE })
      if (seq !== buildingSeq) return
      rows.push(...(res.data.records || []))
    }
    buildingOptions.value = [...new Set(rows.map((room) => room.building).filter(Boolean))].sort((left, right) =>
      String(left).localeCompare(String(right), 'zh-CN')
    )
  } catch {
    if (seq !== buildingSeq) return
    mergeLoadedBuildings()
    // 字典失败不阻断列表；统一错误提示已由 request.js 处理。
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
  query.building = null
  query.type = null
  query.status = null
  query.page = 1
  if (!pushRoute()) loadData()
}

/** 表格多选 */
function handleSelectionChange(rows) {
  selectedIds.value = rows.map((r) => r.id)
}

/** 打开新增/编辑弹窗 */
function openDialog(row) {
  if (row) {
    Object.assign(form, emptyForm, {
      id: row.id,
      name: row.name,
      building: row.building,
      roomNo: row.roomNo,
      type: row.type,
      capacity: row.capacity,
      equipment: row.equipment || '',
      description: row.description || ''
    })
  } else {
    Object.assign(form, emptyForm)
  }
  dialogVisible.value = true
}

/** 提交新增/编辑 */
async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    if (form.id) {
      await updateClassroom({ ...form })
      ElMessage.success('修改成功')
    } else {
      await createClassroom({ ...form })
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    loadData()
    loadBuildingOptions()
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    submitting.value = false
  }
}

/** 重置表单 */
function resetForm() {
  formRef.value && formRef.value.resetFields()
  Object.assign(form, emptyForm)
}

/** 删除教室（二次确认；有预约记录时后端返回 400 提示） */
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除教室「${row.name}（${row.roomNo}）」吗？删除后不可恢复。`,
      '删除确认',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
    )
    await deleteClassroom(row.id)
    ElMessage.success('删除成功')
    loadData()
    loadBuildingOptions()
  } catch {
    // 用户取消或接口报错（统一提示）
  }
}

/** 启用/停用（二次确认） */
async function handleToggleStatus(row) {
  const action = row.status === 1 ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确定要${action}教室「${row.name}」吗？${action === '停用' ? '停用后该教室将不可预约。' : ''}`,
      `${action}确认`,
      { confirmButtonText: `确定${action}`, cancelButtonText: '取消', type: 'warning' }
    )
    await updateClassroomStatus(row.id, row.status === 1 ? 0 : 1)
    ElMessage.success(`${action}成功`)
    loadData()
  } catch {
    // 用户取消或接口报错（统一提示）
  }
}

/** 批量启用/停用（二次确认） */
async function handleBatchStatus(status) {
  const action = status === 1 ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      `确定要批量${action}选中的 ${selectedIds.value.length} 间教室吗？`,
      `批量${action}确认`,
      { confirmButtonText: `确定${action}`, cancelButtonText: '取消', type: 'warning' }
    )
    await batchClassroomStatus(selectedIds.value, status)
    ElMessage.success(`批量${action}成功`)
    loadData()
  } catch {
    // 用户取消或接口报错（统一提示）
  }
}

/** 时间格式化 */
function formatTime(time) {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

onMounted(loadBuildingOptions)
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

/* 表格容器：白面 + 24px 内边距，标题行右侧放批量操作 */
.table-band {
  padding: var(--space-xl);
}
.title-left .section-note {
  margin-left: var(--space-sm);
  font-weight: 400;
}
.band-actions :deep(.el-button) {
  margin-left: var(--space-sm);
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

@media (max-width: 900px) {
  .section-title {
    flex-wrap: wrap;
    gap: var(--space-md);
  }
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
