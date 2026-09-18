<template>
  <div class="classroom-list-page page-container">
    <!-- 页面标题 -->
    <div class="page-head">
      <span class="page-title">教室列表</span>
      <span class="page-tip">共 {{ total }} 间教室 · 支持关键词 / 楼栋 / 类型 / 日期筛选</span>
    </div>

    <!-- AI 智能推荐卡片（R7，需求文档 2.4：教室列表页顶部「AI 为你推荐」；enabled=false 时整卡隐藏） -->
    <AiRecommendCard
      :ai-enabled="aiEnabled"
      :loading="aiLoading"
      :recommendations="recommendations"
    />

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
          <!-- AI 快速预约入口（R7，需求文档 2.4：搜索栏旁；enabled=false 时入口隐藏） -->
          <AiQuickReserve :ai-enabled="aiEnabled" />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 教室卡片列表（Bento 卡片网格） -->
    <div v-loading="loading" class="card-grid">
      <div
        v-for="room in records"
        :key="room.id"
        class="room-card"
        @click="goDetail(room)"
      >
        <div class="room-card-head">
          <div class="room-type-icon" :class="`type-${room.type}`">
            <el-icon :size="22"><component :is="typeIcon(room.type)" /></el-icon>
          </div>
          <div class="room-name-wrap">
            <span class="room-name">{{ room.name }}</span>
            <span class="room-no">{{ room.roomNo }}</span>
          </div>
          <el-tag :type="statusTagType(room.statusLabel)" effect="light" size="small" round>
            <span class="status-dot" :class="statusDot(room.statusLabel)"></span>
            {{ room.statusLabel }}
          </el-tag>
        </div>

        <div class="room-meta">
          <span class="meta-item">
            <el-icon><Location /></el-icon>{{ room.building || '-' }} 楼栋
          </span>
          <span class="meta-item">
            <el-icon><User /></el-icon>容量 {{ room.capacity }} 人
          </span>
          <el-tag :type="typeTagType(room.type)" size="small" effect="plain" round>{{ typeText(room.type) }}</el-tag>
        </div>

        <!-- 今日剩余可预约时段（需求 1.3 冲优项；后端返回 null/undefined 时隐藏，向后兼容） -->
        <div v-if="room.todayRemainingSlots !== undefined && room.todayRemainingSlots !== null" class="remain-slots">
          <el-icon><Clock /></el-icon>今日剩余 <b>{{ room.todayRemainingSlots }}</b> 时段可约
        </div>

        <div v-if="query.date && room.occupiedSlots && room.occupiedSlots.length" class="occupied-bar">
          <span>{{ query.date }} 当天已有 <b>{{ room.occupiedSlots.length }}</b> 个时段被预约</span>
        </div>

        <p class="room-equipment" v-if="room.equipment">
          <el-icon><Monitor /></el-icon>设备：{{ room.equipment }}
        </p>
        <p class="room-desc">{{ room.description || '暂无备注' }}</p>

        <div class="room-foot">
          <el-button type="primary" size="small" plain @click.stop="goDetail(room)">
            查看详情并预约
          </el-button>
        </div>
      </div>

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
import { Clock, Location, Monitor, Refresh, Search, User } from '@element-plus/icons-vue'
import { Notebook, Cpu, School } from '@element-plus/icons-vue'
import { listClassrooms } from '@/api/classroom'
import AiRecommendCard from '@/components/ai/AiRecommendCard.vue'
import AiQuickReserve from '@/components/ai/AiQuickReserve.vue'
import { probeAiRecommend } from '@/utils/aiProbe'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const records = ref([])
const total = ref(0)
const buildingOptions = ref([])

/* ===== AI 智能推荐（R7：探测一次 AI 可用性，推荐卡与快速预约入口共用）===== */
const aiEnabled = ref(false)
const aiLoading = ref(false)
const recommendations = ref([])

/**
 * 加载智能推荐 Top3（AI 只读；失败静默降级为隐藏，不阻断页面）。
 * N5：统一走 probeAiRecommend（按 userId 缓存合并并发，与 AiAssistant 共用同一份结果，
 * 进入布局 + 列表页只发一次 /api/ai/recommend）
 */
async function loadRecommend() {
  aiLoading.value = true
  try {
    const res = await probeAiRecommend()
    aiEnabled.value = !!(res && res.enabled === true)
    if (aiEnabled.value) {
      recommendations.value = (res && res.recommendations) || []
    }
  } catch (e) {
    aiEnabled.value = false
  } finally {
    aiLoading.value = false
  }
}

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

/** 类型图标 */
function typeIcon(type) {
  return { 1: School, 2: Cpu, 3: Notebook }[type] || School
}

/** 实时状态标签色（R4 三态：空闲绿/使用中红/已结束灰） */
function statusTagType(label) {
  return { 当前空闲: 'success', 使用中: 'danger', 已结束: 'info' }[label] || 'info'
}

/** 状态圆点 */
function statusDot(label) {
  return { 当前空闲: 'dot-free', 使用中: 'dot-busy', 已结束: 'dot-done' }[label] || 'dot-done'
}

/** 筛选条件变化时自动记忆（还原后不再触发保存的抖动已由条件一致规避） */
watch(
  () => [query.keyword, query.building, query.type, query.date],
  () => saveFilter()
)

onMounted(() => {
  restoreFilter()
  loadData()
  loadRecommend()
})
</script>

<style scoped>
.page-tip {
  font-size: 13px;
  color: var(--text-secondary);
}

.search-card {
  margin-bottom: 18px;
  border-radius: var(--radius-lg);
}
.search-card :deep(.el-form-item) {
  margin-bottom: 0;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 18px;
  min-height: 200px;
}

/* ---- 教室卡片（扁平面板：无顶部装饰条、无浮起） ---- */
.room-card {
  background: #fff;
  border-radius: var(--radius-lg);
  border: 1px solid var(--border-color-light);
  box-shadow: var(--shadow-card);
  padding: 18px 18px 14px;
  cursor: pointer;
  position: relative;
  overflow: hidden;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
  display: flex;
  flex-direction: column;
}
.room-card:hover {
  box-shadow: var(--shadow-hover);
  border-color: var(--border-color);
}

.room-card-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.room-type-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.room-type-icon.type-1 {
  background: var(--brand-primary-light);
  color: var(--brand-primary);
}
.room-type-icon.type-2 {
  background: var(--brand-success-light);
  color: var(--brand-success);
}
.room-type-icon.type-3 {
  background: var(--brand-warning-light);
  color: var(--brand-warning);
}

.room-name-wrap {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.room-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.room-no {
  font-size: 12px;
  color: var(--text-placeholder);
}

.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 4px;
  vertical-align: 1px;
}
.dot-free {
  background: var(--brand-success);
}
.dot-busy {
  background: var(--brand-danger);
}
.dot-done {
  background: var(--brand-info);
}

.room-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  color: var(--text-regular);
  font-size: 13px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.meta-item .el-icon {
  color: var(--text-placeholder);
  font-size: 14px;
}

.remain-slots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--text-secondary);
  font-size: 12.5px;
  margin-bottom: 10px;
}
.remain-slots .el-icon {
  color: var(--brand-info);
  font-size: 14px;
}
.remain-slots b {
  color: var(--brand-primary);
  font-weight: 600;
}

.occupied-bar {
  background: var(--brand-warning-light);
  border: 1px solid var(--brand-warning-light);
  color: var(--brand-warning);
  font-size: 12px;
  padding: 6px 10px;
  border-radius: 8px;
  margin-bottom: 10px;
}
.occupied-bar b {
  font-weight: 700;
}

.room-equipment {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 5px;
}
.room-equipment .el-icon {
  font-size: 14px;
  color: var(--text-placeholder);
}

.room-desc {
  font-size: 13px;
  color: var(--text-regular);
  margin-bottom: 12px;
  flex: 1;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.room-foot {
  display: flex;
  justify-content: flex-end;
  border-top: 1px dashed var(--border-color-light);
  padding-top: 12px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 20px;
}

/* ---------- 窄屏适配（P3-8：<480px 单列卡片 + 分页换行，避免横向溢出） ---------- */
@media (max-width: 480px) {
  .card-grid {
    grid-template-columns: 1fr;
  }
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
