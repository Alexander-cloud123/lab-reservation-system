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
          <el-button text type="primary" :icon="filtersExpanded ? ArrowUp : ArrowDown" :aria-expanded="filtersExpanded" @click="filtersExpanded = !filtersExpanded">
            {{ filtersExpanded ? '收起筛选' : '更多筛选' }}
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 教室表格：白面表格容器 -->
    <div class="table-band">
      <div class="section-title">
        <span class="title-left">
          教室列表
          <span class="section-note">共 {{ total }} 间，已选 {{ selectedIds.length }} 间</span>
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
          <div class="empty-slate">
            没有找到符合条件的教室<span class="empty-action" @click="handleReset">重置筛选</span>
          </div>
        </template>
      </el-table>

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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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
  loading.value = true
  try {
    const params = {
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      building: query.building || undefined,
      type: query.type === null || query.type === '' ? undefined : query.type,
      status: query.status === null || query.status === '' ? undefined : query.status
    }
    const res = await pageClassrooms(params)
    records.value = res.data.records
    total.value = res.data.total
    collectBuildings()
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    loading.value = false
  }
}

/** 收集当前页楼栋（供筛选下拉），跨页合并去重 */
function collectBuildings() {
  records.value.forEach((r) => {
    if (r.building && !buildingOptions.value.includes(r.building)) {
      buildingOptions.value.push(r.building)
    }
  })
}

/** 查询（重置页码后加载） */
function handleSearch() {
  query.page = 1
  loadData()
}

/** 重置筛选条件 */
function handleReset() {
  query.keyword = ''
  query.building = null
  query.type = null
  query.status = null
  query.page = 1
  loadData()
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

onMounted(loadData)
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
.empty-slate {
  line-height: 1.6;
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
