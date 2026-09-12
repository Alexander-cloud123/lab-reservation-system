<template>
  <div class="classroom-list-page">
    <!-- 搜索栏：关键词 / 楼栋 / 类型 / 日期筛选 -->
    <el-card shadow="never" class="search-card">
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
        <el-form-item label="楼栋">
          <el-select v-model="query.building" placeholder="全部" clearable style="width: 130px">
            <el-option v-for="b in buildingOptions" :key="b" :label="b" :value="b" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.type" placeholder="全部" clearable style="width: 140px">
            <el-option label="普通教室" :value="1" />
            <el-option label="实验室" :value="2" />
            <el-option label="机房" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker
            v-model="query.date"
            type="date"
            placeholder="查看某日占用情况"
            value-format="YYYY-MM-DD"
            :disabled-date="(d) => d && d.getTime() < Date.now() - 86400000"
            clearable
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 教室卡片列表 -->
    <div v-loading="loading" class="card-grid">
      <el-card
        v-for="room in records"
        :key="room.id"
        shadow="hover"
        class="room-card"
        @click="goDetail(room)"
      >
        <div class="room-card-head">
          <span class="room-name">{{ room.name }}</span>
          <el-tag :type="statusTagType(room.statusLabel)" size="small">
            {{ room.statusLabel }}
          </el-tag>
        </div>
        <div class="room-meta">
          <span class="room-no">编号：{{ room.roomNo }}</span>
          <span class="room-building">楼栋：{{ room.building }}</span>
        </div>
        <div class="room-tags">
          <el-tag :type="typeTagType(room.type)" size="small">{{ typeText(room.type) }}</el-tag>
          <el-tag size="small" type="info">容量 {{ room.capacity }} 人</el-tag>
          <el-tag v-if="query.date && room.occupiedSlots && room.occupiedSlots.length" size="small" type="warning">
            {{ query.date }} 已约 {{ room.occupiedSlots.length }} 个时段
          </el-tag>
        </div>
        <p class="room-equipment">设备：{{ room.equipment || '-' }}</p>
        <p class="room-desc">{{ room.description || '暂无备注' }}</p>
        <div class="room-foot">
          <el-button type="primary" size="small" plain @click.stop="goDetail(room)">查看详情并预约</el-button>
        </div>
      </el-card>

      <!-- 空状态 -->
      <el-empty v-if="!loading && !records.length" description="没有找到符合条件的教室，换个条件试试吧">
        <el-button type="primary" @click="handleReset">重置筛选</el-button>
      </el-empty>
    </div>

    <!-- 分页 -->
    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.size"
        :total="total"
        :page-sizes="[6, 12, 24]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @size-change="loadData"
        @current-change="loadData"
      />
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Search, Refresh } from '@element-plus/icons-vue'
import { listClassrooms } from '@/api/classroom'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const records = ref([])
const total = ref(0)
const buildingOptions = ref([])

/**
 * 筛选条件记忆（R4 体验细节）：关键词/楼栋/类型/日期存入 localStorage（key 带用户维度），
 * 切换页面返回后自动还原
 */
const FILTER_KEY = () => `reservation_filter_${userStore.userInfo ? userStore.userInfo.id : ''}`

/** 查询条件（含分页；分页不记忆，仅记忆筛选条件） */
const query = reactive({
  page: 1,
  size: 12,
  keyword: '',
  building: null,
  type: null,
  date: null
})

/** 保存筛选条件到 localStorage */
function saveFilter() {
  localStorage.setItem(
    FILTER_KEY(),
    JSON.stringify({
      keyword: query.keyword || '',
      building: query.building || null,
      type: query.type === null || query.type === '' ? null : query.type,
      date: query.date || null
    })
  )
}

/** 还原上次筛选条件 */
function restoreFilter() {
  try {
    const saved = JSON.parse(localStorage.getItem(FILTER_KEY()) || 'null')
    if (saved) {
      query.keyword = saved.keyword || ''
      query.building = saved.building || null
      query.type = saved.type === undefined || saved.type === null ? null : saved.type
      query.date = saved.date || null
    }
  } catch (e) {
    // 本地数据损坏时忽略，使用默认条件
  }
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
      date: query.date || undefined
    }
    const res = await listClassrooms(params)
    records.value = res.data.records
    total.value = res.data.total
    collectBuildings()
  } catch (e) {
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
  saveFilter()
  loadData()
}

/** 重置筛选条件 */
function handleReset() {
  query.keyword = ''
  query.building = null
  query.type = null
  query.date = null
  query.page = 1
  saveFilter()
  loadData()
}

/** 跳转教室详情 */
function goDetail(room) {
  router.push(`/student/classrooms/${room.id}`)
}

/** 类型文案 */
function typeText(type) {
  return { 1: '普通教室', 2: '实验室', 3: '机房' }[type] || '未知'
}

/** 类型标签色 */
function typeTagType(type) {
  return { 1: 'primary', 2: 'success', 3: 'warning' }[type] || 'info'
}

/** 实时状态标签色（R4 三态：空闲绿/使用中红/已结束灰） */
function statusTagType(label) {
  return { 当前空闲: 'success', 使用中: 'danger', 已结束: 'info' }[label] || 'info'
}

/** 筛选条件变化时自动记忆（还原后不再触发保存的抖动已由条件一致规避） */
watch(
  () => [query.keyword, query.building, query.type, query.date],
  () => saveFilter()
)

onMounted(() => {
  restoreFilter()
  loadData()
})
</script>

<style scoped>
.classroom-list-page {
  max-width: 1100px;
  margin: 0 auto;
}

.search-card {
  margin-bottom: 16px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(330px, 1fr));
  gap: 16px;
  min-height: 200px;
}

.room-card {
  cursor: pointer;
}

.room-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.room-name {
  font-size: 16px;
  font-weight: 600;
  color: #1f3a93;
}

.room-meta {
  display: flex;
  gap: 16px;
  color: #606266;
  font-size: 13px;
  margin-bottom: 8px;
}

.room-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.room-equipment {
  font-size: 13px;
  color: #909399;
  margin-bottom: 4px;
}

.room-desc {
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
}

.room-foot {
  display: flex;
  justify-content: flex-end;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
