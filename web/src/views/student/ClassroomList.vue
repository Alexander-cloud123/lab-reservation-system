<template>
  <div class="classroom-list-page">
    <!-- 时刻带：唯一满铺的墨蓝块，说明现在处在一天中的什么位置 -->
    <TimeBand class="list-band" :summary="bandSummary" />

    <div class="page-container">
    <!-- 页面标题 -->
    <div class="page-head reveal">
      <span class="page-title">教室列表</span>
      <span class="page-tip">共 {{ total }} 间教室，可按关键词、楼栋、类型与日期筛选</span>
    </div>

    <!-- AI 智能推荐卡片（R7，需求文档 2.4：教室列表页顶部「AI 为你推荐」；enabled=false 时整卡隐藏） -->
    <AiRecommendCard
      :ai-enabled="aiEnabled"
      :loading="aiLoading"
      :recommendations="recommendations"
      :message="aiNotice"
      class="reveal stagger"
    />

    <!-- 搜索栏：关键词 / 楼栋 / 类型 / 日期筛选 -->
    <div class="search-card table-band reveal stagger">
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
            <el-option v-for="t in ROOM_TYPES" :key="t.value" :label="t.label" :value="t.value" />
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
    </div>

    <!-- 教室卡片列表（Bento 卡片网格） -->
    <div v-loading="loading" class="card-grid">
      <div
        v-for="(room, index) in records"
        :key="room.id"
        class="room-card reveal stagger"
        :style="{ '--i': Math.min(index, 12) }"
        @click="goDetail(room)"
      >
        <div class="room-card-head">
          <div class="room-name-wrap">
            <span class="room-name">{{ room.name }}</span>
            <span class="room-no">{{ room.roomNo }}</span>
          </div>
          <span class="status-cell" :class="statusPill(room.statusLabel)">{{ room.statusLabel }}</span>
        </div>

        <div class="room-meta">
          <span class="meta-item room-type">{{ typeText(room.type) }}</span>
          <span class="meta-item">{{ room.building || '-' }} 楼栋</span>
          <span class="meta-item">容量 {{ room.capacity }} 人</span>
        </div>

        <!-- 今日剩余可预约时段（需求 1.3 冲优项；后端返回 null/undefined 时隐藏，向后兼容） -->
        <div v-if="room.todayRemainingSlots !== undefined && room.todayRemainingSlots !== null" class="remain-slots">
          今日剩余 <b>{{ room.todayRemainingSlots }}</b> 时段可约
        </div>

        <div v-if="query.date && room.occupiedSlots && room.occupiedSlots.length" class="occupied-bar">
          <span>{{ query.date }} 当天已有 <b>{{ room.occupiedSlots.length }}</b> 个时段被预约</span>
        </div>

        <p class="room-equipment" v-if="room.equipment">设备：{{ room.equipment }}</p>
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
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import TimeBand from '@/components/common/TimeBand.vue'
import { useRouter } from 'vue-router'
import { Refresh, Search } from '@element-plus/icons-vue'
import { listClassrooms } from '@/api/classroom'
import AiRecommendCard from '@/components/ai/AiRecommendCard.vue'
import AiQuickReserve from '@/components/ai/AiQuickReserve.vue'
import { probeAiRecommend } from '@/utils/aiProbe'
import { useUserStore } from '@/stores/user'
import { ROOM_TYPES, typeText } from '@/utils/dict'

/** 时刻带摘要：今天还有多少间教室有空档（按当前列表数据算，不额外请求） */
const bandSummary = computed(() => {
  const rooms = records.value || []
  const freeRooms = rooms.filter((room) => Number(room.todayRemainingSlots || 0) > 0)
  if (!freeRooms.length) {
    return `今天已开放 ${rooms.length} 间教室查询，先挑楼栋和时段看看`
  }
  return `今天还有 ${freeRooms.length} 间教室正好空着，可以直接约`
})
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
/** 推荐降级提示（后端 message，非空 = 当前为本地规则结果） */
const aiNotice = ref('')

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
      // 降级提示透传到推荐卡（probe 已按 userId 缓存，此处仅取值）
      aiNotice.value = (res && res.message) || ''
    }
  } catch {
    aiEnabled.value = false
    aiNotice.value = ''
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
  } catch {
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




/** 状态药丸色：空闲=通过绿，使用中=警示橙，已结束=中性 */
function statusPill(label) {
  return { 当前空闲: 'is-approved', 使用中: 'is-pending', 已结束: 'is-canceled' }[label] || 'is-canceled'
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
/* 时刻带满铺在页面最上方，其余内容交给 .page-container 的内距 */
.list-band {
  margin-bottom: var(--space-xl);
}

.page-tip {
  font-size: var(--text-sm);
  line-height: 1.6;
  color: var(--ink-soft);
}

/* 筛选条：一页一张白面，控件同高，标签与控件同基线 */
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
  padding-right: 10px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--space-lg);
  min-height: 200px;
}

/* ---- 教室卡：白面 + 12px 圆角 + 一层浅影，hover 抬起 ---- */
.room-card {
  display: flex;
  flex-direction: column;
  padding: var(--space-xl);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-surface);
  cursor: pointer;
  transition: box-shadow 0.16s ease, border-color 0.16s ease, transform 0.16s ease;
}
.room-card:hover {
  border-color: var(--brand-primary-border);
  box-shadow: var(--shadow-raised);
  transform: translateY(-2px);
}
.room-card:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

.room-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-md);
}

.room-name-wrap {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}
.room-name {
  font-size: var(--text-md);
  font-weight: 600;
  line-height: 1.4;
  color: var(--ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* 门牌编号：白面细描边 + tabular 数字 */
.room-no {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 8px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
  font-size: var(--text-sm);
  font-weight: 600;
  line-height: 1;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.room-meta {
  display: flex;
  align-items: center;
  gap: var(--space-lg);
  color: var(--ink-soft);
  font-size: var(--text-sm);
  line-height: 1.6;
  margin-bottom: var(--space-md);
  flex-wrap: wrap;
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.meta-item .el-icon {
  color: var(--ink-soft);
  font-size: var(--text-sm);
}

.remain-slots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ink-soft);
  font-size: var(--text-sm);
  line-height: 1.6;
  margin-bottom: var(--space-sm);
}
.remain-slots b {
  margin: 0 2px;
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--brand);
  font-variant-numeric: tabular-nums;
}

.occupied-bar {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  align-self: flex-start;
  background: var(--warning-soft);
  color: var(--warning);
  font-size: var(--text-xs);
  line-height: 1.5;
  padding: 4px 10px;
  border-radius: var(--radius-sm);
  margin-bottom: var(--space-sm);
}
.occupied-bar b {
  font-weight: 600;
}

.room-equipment {
  font-size: var(--text-sm);
  line-height: 1.6;
  color: var(--ink-soft);
  margin-bottom: var(--space-sm);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.room-desc {
  font-size: var(--text-sm);
  line-height: 1.7;
  color: var(--ink-soft);
  margin-bottom: var(--space-lg);
  flex: 1;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.room-foot {
  display: flex;
  justify-content: flex-start;
  border-top: 1px solid var(--line);
  padding-top: var(--space-md);
  margin-top: auto;
}
.room-foot :deep(.el-button) {
  height: 34px;
  padding: 0 2px;
  border: 0;
  background: transparent;
  font-weight: 600;
  color: var(--brand);
}
.room-foot :deep(.el-button:hover) {
  background: transparent;
  color: var(--brand-primary-hover);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-xl);
}

/* ---------- 窄屏适配 ---------- */
@media (max-width: 560px) {
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