<template>
  <div class="record-page">
    <!-- 页面标题栏 -->
    <div class="page-head">
      <span class="page-title">预约记录</span>
      <span class="page-tip">全量预约记录查询与 Excel 导出</span>
    </div>
    <!-- 搜索栏：日期范围 / 教室 / 用户 / 状态 多条件筛选（需求文档 2.4 第 12 页） -->
    <el-card shadow="never" class="search-card">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="日期范围">
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
        <el-form-item label="教室">
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
        <el-form-item label="用户">
          <el-input
            v-model="query.keyword"
            placeholder="账号 / 姓名"
            clearable
            style="width: 160px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 110px">
            <el-option label="待审核" :value="0" />
            <el-option label="已通过" :value="1" />
            <el-option label="已驳回" :value="2" />
            <el-option label="已取消" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <div class="toolbar">
        <span class="toolbar-tip">共 {{ total }} 条记录，可按条件导出 Excel</span>
        <el-button
          type="primary"
          plain
          :icon="Download"
          :loading="exporting"
          @click="handleExport"
        >
          导出 Excel
        </el-button>
      </div>

      <el-table v-loading="loading" :data="records" stripe>
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
        <el-table-column prop="reserveDate" label="预约日期" width="110" />
        <el-table-column label="时段" width="120">
          <template #default="{ row }">{{ row.startTime }}-{{ row.endTime }}</template>
        </el-table-column>
        <el-table-column prop="purpose" label="用途" min-width="140" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="auditRemark" label="审核备注" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.auditRemark || '-' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>

      <!-- 空状态 -->
      <el-empty v-if="!loading && !records.length" description="暂无符合条件的预约记录" />

      <!-- 分页 -->
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="handleSearch"
          @current-change="loadData"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { Search, Refresh, Download } from '@element-plus/icons-vue'
import { pageManageReservations, exportReservations } from '@/api/reservation'
import { pageClassrooms } from '@/api/classroom'
import { statusText, statusTagType } from '@/utils/dict'

const loading = ref(false)
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

/** 组装查询参数（空值剔除，避免向后端传多余参数） */
function buildParams() {
  const params = { page: query.page, size: query.size }
  if (query.status !== undefined && query.status !== null && query.status !== '') params.status = query.status
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
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  }
}

/** 加载预约记录列表 */
async function loadData() {
  loading.value = true
  try {
    const res = await pageManageReservations(buildParams())
    records.value = res.data.records
    total.value = res.data.total
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  } finally {
    loading.value = false
  }
}

/** 查询（重置页码）/ 重置 */
function handleSearch() {
  query.page = 1
  loadData()
}

function handleReset() {
  query.status = undefined
  query.classroomId = undefined
  query.keyword = ''
  dateRange.value = null
  query.page = 1
  loadData()
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
  } catch (e) {
    // 错误提示已由 request.js 统一处理（含 401/403/业务错误）
  } finally {
    exporting.value = false
  }
}

/** 创建时间展示（yyyy-MM-dd HH:mm:ss） */
function formatDateTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

onMounted(() => {
  loadData()
  loadClassrooms()
})
</script>

<style scoped>
.record-page {
  padding: 4px;
}

.page-tip {
  font-size: 13px;
  color: var(--text-secondary);
}

.search-card {
  margin-bottom: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.toolbar-tip {
  font-size: 13px;
  color: var(--text-secondary);
}

.user-cell,
.room-cell {
  display: flex;
  flex-direction: column;
}

.user-account,
.room-no {
  font-size: 12px;
  color: var(--text-placeholder);
}

.room-name {
  color: var(--text-primary);
  font-weight: 600;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
