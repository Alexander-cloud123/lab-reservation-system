<template>
  <div class="my-reservation-page">
    <el-card shadow="never">
      <!-- 状态分类查看 -->
      <el-tabs v-model="activeStatus" @tab-change="handleTabChange">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane label="待审核" name="0" />
        <el-tab-pane label="已通过" name="1" />
        <el-tab-pane label="已驳回" name="2" />
        <el-tab-pane label="已取消" name="3" />
      </el-tabs>

      <el-table v-loading="loading" :data="records" stripe empty-text="">
        <el-table-column prop="classroomName" label="教室" min-width="160">
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
        <el-table-column prop="purpose" label="用途" min-width="160" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="auditRemark" label="审核备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.auditRemark || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 0 || row.status === 1"
              size="small"
              type="danger"
              plain
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
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMyReservations, cancelReservation } from '@/api/reservation'

const router = useRouter()
const loading = ref(false)
const records = ref([])
const total = ref(0)
const activeStatus = ref('all')

/** 查询条件（含分页） */
const query = reactive({
  page: 1,
  size: 10,
  status: undefined
})

/** 加载我的预约 */
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
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  } finally {
    loading.value = false
  }
}

/** 切换状态标签页 */
function handleTabChange(name) {
  query.status = name === 'all' ? undefined : Number(name)
  query.page = 1
  loadData()
}

/** 取消预约（二次确认） */
async function handleCancel(row) {
  try {
    await ElMessageBox.confirm(
      `确定要取消「${row.classroomName}」${row.reserveDate} ${row.startTime}-${row.endTime} 的预约吗？取消后不可恢复。`,
      '取消预约确认',
      { confirmButtonText: '确定取消', cancelButtonText: '再想想', type: 'warning' }
    )
    await cancelReservation(row.id)
    ElMessage.success('取消成功')
    loadData()
  } catch (e) {
    // 用户取消或接口报错（统一提示）
  }
}

/** 去教室列表 */
function goList() {
  router.push('/student/home')
}

/** 状态文案 */
function statusText(status) {
  return { 0: '待审核', 1: '已通过', 2: '已驳回', 3: '已取消' }[status] || '未知'
}

/** 状态标签色 */
function statusTagType(status) {
  return { 0: 'warning', 1: 'success', 2: 'danger', 3: 'info' }[status] || 'info'
}

onMounted(loadData)
</script>

<style scoped>
.my-reservation-page {
  max-width: 1000px;
  margin: 0 auto;
}

.room-cell {
  display: flex;
  flex-direction: column;
}

.room-name {
  color: #1f3a93;
  font-weight: 500;
}

.room-no {
  font-size: 12px;
  color: #909399;
}

.no-action {
  color: #c0c4cc;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
