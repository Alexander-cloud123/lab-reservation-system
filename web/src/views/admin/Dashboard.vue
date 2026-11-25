<template>
  <div class="dashboard-page page-container">
    <!-- 页头：标题 + 说明 + 右侧时间范围与刷新 -->
    <div class="page-head">
      <div>
        <h1 class="page-title">数据看板</h1>
        <p class="page-sub">教室使用、时段分布与预约趋势随所选时间区间联动</p>
      </div>
      <div class="head-actions">
        <el-radio-group v-model="quickRange" @change="handleQuickRange">
          <el-radio-button value="7">近 7 天</el-radio-button>
          <el-radio-button value="30">近 30 天</el-radio-button>
          <el-radio-button value="90">近 90 天</el-radio-button>
          <el-radio-button value="year">本年</el-radio-button>
        </el-radio-group>
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="YYYY-MM-DD"
          style="width: 260px"
          @change="handleDateRangeChange"
        />
        <el-button type="primary" :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </div>

    <div v-if="loadError" class="dashboard-error" role="alert">
      <span>{{ hasLoaded ? '数据可能未更新，当前保留上次成功的图表。' : '图表数据加载失败，请重试。' }}</span>
      <el-button link type="primary" @click="loadAll">重试</el-button>
    </div>

    <!-- 三块图表白面：使用率排行 / 热门时段 / 月度趋势，32px 留白分隔 -->
    <div class="chart-grid">
      <div class="surface chart-card">
        <h2 class="chart-title">教室使用率排行</h2>
        <p class="chart-sub">已通过预约占用小时 / 区间可预约小时</p>
        <div
          ref="usageRateEl"
          class="chart-box"
          role="img"
          aria-label="教室使用率排行柱状图"
        />
        <DashboardChartTable
          caption="教室使用率排行数据"
          :columns="usageColumns"
          :rows="usageRows"
        />
      </div>
      <div class="surface chart-card">
        <h2 class="chart-title">热门时段分布</h2>
        <p class="chart-sub">已通过预约按开始时段</p>
        <div ref="timeDistEl" class="chart-box" role="img" aria-label="热门时段分布饼图" />
        <DashboardChartTable
          caption="热门时段分布数据"
          :columns="timeDistributionColumns"
          :rows="timeDistributionRows"
        />
      </div>
    </div>
    <div class="surface chart-card">
      <h2 class="chart-title">月度预约趋势</h2>
      <p class="chart-sub">全部状态预约条数</p>
      <div
        ref="trendEl"
        class="chart-box chart-box-tall"
        role="img"
        aria-label="月度预约趋势折线图"
      />
      <DashboardChartTable
        caption="月度预约趋势数据"
        :columns="trendColumns"
        :rows="trendRows"
      />
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
// ECharts 按需引入（R6 联调优化：避免全量包约 1MB 打入 Dashboard chunk，依赖版本不变）
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
  GraphicComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import dayjs from 'dayjs'
import { getUsageRate, getTrend, getTimeDistribution } from '@/api/stats'
import DashboardChartTable from '@/components/admin/DashboardChartTable.vue'
import { compactQuery, queryDate, queryEnum, queryText, sameQuery } from '@/utils/routeQuery'

// 为满足 multi-word 规则并便于 devtools 辨识
defineOptions({ name: 'DashboardView' })

// 注册看板用到的图表类型与组件（柱状图/折线图/饼图 + 提示框/网格/图例 + 空数据提示 graphic 文本 + Canvas 渲染）
echarts.use([
  BarChart,
  LineChart,
  PieChart,
  TooltipComponent,
  GridComponent,
  LegendComponent,
  GraphicComponent,
  CanvasRenderer
])

/**
 * 图表配色：与 main.css 命名色保持一致
 * Canvas 读不到 CSS 变量，只能按值引用；这是本文件唯一允许出现的十六进制，且集中在此对象中。
 */
/**
 * 图表取色：与 main.css 的命名色保持同一份（ECharts 读不到 CSS 变量，故此处集中硬编码）。
 * 第四轮（晨光玻璃）后为「天光蓝 → 薄荷青 → 晨光橙」，与页面渐变同一族。
 */
const C = {
  brand: '#1d6fe0', // --sky
  aqua: '#22c3b3', // --aqua
  deep: '#0b2a5b', // --deep
  sun: '#ff9a3c', // --sun
  ink: '#0b1b2b', // --ink
  soft: '#45566b', // --ink-soft
  line: 'rgba(15, 42, 80, 0.12)', // --line
  success: '#0e9b6f', // --success
  warning: '#b9761f', // --warning
  attention: '#e08a2b' // --attention
}

/** 饼图分段色：品牌蓝 + 功能色，无紫/霓虹 */
const PIE_PALETTE = [C.brand, C.aqua, C.sun, C.deep, C.soft]

/** 图表入场：一次 320ms 生长，三图错开 80ms 依次到位（不做循环动画） */
const CHART_ANIM = { animation: true, animationDuration: 320, animationEasing: 'cubicOut' }
const DEFAULT_RANGE = '30'
const RANGE_OPTIONS = ['7', '30', '90', 'year']
const route = useRoute()
const router = useRouter()
const loading = ref(false)
const loadError = ref(false)
const hasLoaded = ref(false)
const quickRange = ref(DEFAULT_RANGE)
const dateRange = ref([
  dayjs().subtract(29, 'day').format('YYYY-MM-DD'),
  dayjs().format('YYYY-MM-DD')
])

const usageRateEl = ref(null)
const trendEl = ref(null)
const timeDistEl = ref(null)
const usageRows = ref([])
const trendRows = ref([])
const timeDistributionRows = ref([])

const usageColumns = [
  { key: 'name', label: '教室' },
  { key: 'roomNo', label: '编号' },
  { key: 'approvedHours', label: '已通过小时' },
  { key: 'usageRate', label: '使用率' }
]
const trendColumns = [
  { key: 'month', label: '月份' },
  { key: 'count', label: '预约数' }
]
const timeDistributionColumns = [
  { key: 'slot', label: '时段' },
  { key: 'count', label: '数量' },
  { key: 'percentage', label: '占比' }
]

let usageChart = null
let trendChart = null
let timeDistChart = null
let loadSeq = 0

/** HTML tooltip 的所有动态值统一转义，静态 <br/> 仅负责保持原有换行格式 */
function escapeHtml(value) {
  return String(value ?? '').replace(
    /[&<>"']/g,
    (char) =>
      ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;'
      })[char]
  )
}

/** 当前区间参数（YYYY-MM-DD） */
function rangeParams() {
  const [start, end] = dateRange.value || []
  return start && end ? { startDate: start, endDate: end } : {}
}

/** 将快捷范围换算为实际查询区间，结束日期均为今天。 */
function datesForRange(key) {
  const today = dayjs()
  if (key === 'year') {
    return [today.startOf('year').format('YYYY-MM-DD'), today.format('YYYY-MM-DD')]
  }
  const days = Number(key)
  return [today.subtract(days - 1, 'day').format('YYYY-MM-DD'), today.format('YYYY-MM-DD')]
}

/** URL 规范化：默认 30 天不写 range；合法自定义区间只保留 start/end。 */
function normalizeRouteQuery(query) {
  const hasDateParams = query.start !== undefined || query.end !== undefined
  if (hasDateParams) {
    const start = queryDate(query.start)
    const end = queryDate(query.end)
    if (!start || !end || start > end) return {}
    return { start, end }
  }
  const range = queryEnum(query.range, RANGE_OPTIONS, DEFAULT_RANGE)
  return compactQuery({ range: range === DEFAULT_RANGE ? '' : range })
}

/** sameQuery 会忽略空值；这里额外识别空参数，确保残缺 URL 也会触发 replace。 */
function hasBlankRangeParam(query) {
  return ['range', 'start', 'end'].some(
    (key) => query[key] !== undefined && queryText(query[key]) === ''
  )
}

/** 将已规范化的 URL 状态回填到筛选控件。 */
function applyQueryToControls(query) {
  if (query.start && query.end) {
    quickRange.value = ''
    dateRange.value = [query.start, query.end]
  } else {
    const range = query.range || DEFAULT_RANGE
    quickRange.value = range
    dateRange.value = datesForRange(range)
  }
}

/** UI 操作统一 push；规范化后相同的 query 不重复导航、不重复请求。 */
function navigateQuery(query) {
  const normalized = compactQuery(query)
  if (sameQuery(route.query, normalized)) {
    // 清空默认区间等操作不会改变 URL，也需把控件恢复为 URL 表达的状态。
    applyQueryToControls(normalized)
    return
  }
  router.push({ query: normalized })
}

/** 快捷范围选择：近 7/30/90 天、本年 */
function handleQuickRange(key) {
  navigateQuery({ range: key === DEFAULT_RANGE ? '' : key })
}

/** 自定义日期范围变化；清空或不完整时回到默认 30 天。 */
function handleDateRangeChange() {
  const [start, end] = dateRange.value || []
  const validStart = queryDate(start)
  const validEnd = queryDate(end)
  navigateQuery(
    validStart && validEnd && validStart <= validEnd
      ? { start: validStart, end: validEnd }
      : {}
  )
}

/** route.query 是唯一状态源：先修正非法 URL，再回填控件并统一加载三图。 */
function syncRouteQuery() {
  const query = normalizeRouteQuery(route.query)
  if (!sameQuery(route.query, query) || hasBlankRangeParam(route.query)) {
    router.replace({ query })
    return
  }

  applyQueryToControls(query)
  loadAll()
}

watch(() => route.query, syncRouteQuery, { immediate: true })

/** 三图数据均来自后端只读统计接口（禁止前端伪造），时间筛选统一作用三图 */
async function loadAll() {
  const seq = ++loadSeq
  loading.value = true
  loadError.value = false
  try {
    const params = rangeParams()
    const [usageRes, trendRes, distRes] = await Promise.all([
      getUsageRate(params),
      getTrend(params),
      getTimeDistribution(params)
    ])
    if (seq !== loadSeq) return
    const usageList = usageRes.data || []
    const trendList = trendRes.data || []
    const distList = distRes.data || []
    renderUsageRate(usageList)
    renderTrend(trendList)
    renderTimeDistribution(distList)
    // 文本替代与图表共用同一次成功结果；任一请求失败时均保留上一份数据。
    usageRows.value = usageList.map((item) => ({
      name: item.name,
      roomNo: item.roomNo,
      approvedHours: item.approvedHours,
      usageRate: `${item.usageRate}%`
    }))
    trendRows.value = trendList.map((item) => ({ month: item.month, count: item.count }))
    timeDistributionRows.value = distList.map((item) => ({
      slot: item.slot,
      count: item.count,
      percentage: `${item.percentage}%`
    }))
    hasLoaded.value = true
    loadError.value = false
  } catch {
    if (seq === loadSeq) loadError.value = true
    // 统一错误提示已由 request.js 处理；不重新渲染，保留上次成功的图表。
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

/** 空数据提示（ECharts graphic 文本占位；数据为空时显示，有数据时 setOption(notMerge=true) 整表替换自动清除） */
function emptyGraphic(text) {
  return [
    {
      type: 'text',
      left: 'center',
      top: 'middle',
      style: {
        text,
        fontSize: 13,
        fill: C.soft
      }
    }
  ]
}

/** 柱状图：教室使用率排行（按使用率倒序，后端已排序；扁平纯色柱 + 顶部标签） */
function renderUsageRate(list) {
  if (!list.length) {
    usageChart.setOption({ graphic: emptyGraphic('暂无教室使用率数据') }, true)
    return
  }
  // X 轴只放门牌号（A101 这类），教室全名留在 tooltip；否则 12 根柱子的长名字必然互相压住
const names = list.map((r) => r.roomNo || r.name)
  const values = list.map((r) => r.usageRate)
  usageChart.setOption(
    {
      ...CHART_ANIM,
      animationDelay: 0,
      tooltip: {
        trigger: 'axis',
        formatter(params) {
          const p = params[0]
          const item = list[p.dataIndex]
          if (!item) {
            return ''
          }
          return `${escapeHtml(item.name)}（${escapeHtml(item.roomNo)}）<br/>使用率：${escapeHtml(item.usageRate)}%<br/>占用时长：${escapeHtml(item.approvedHours)} 小时`
        }
      },
      grid: { left: 48, right: 24, top: 32, bottom: 56 },
      xAxis: {
        type: 'category',
        data: names,
        axisLabel: { rotate: 0, fontSize: 11, color: C.soft, interval: 0, margin: 12 },
        axisLine: { lineStyle: { color: C.line } },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'value',
        name: '使用率（%）',
        nameTextStyle: { color: C.soft, fontSize: 12 },
        axisLabel: { formatter: '{value}%', fontSize: 12, color: C.soft },
        axisLine: { lineStyle: { color: C.line } },
        splitLine: { lineStyle: { color: C.line } }
      },
      series: [
        {
          type: 'bar',
          data: values,
          barMaxWidth: 32,
          itemStyle: {
            borderRadius: [6, 6, 0, 0],
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: C.brand },
                { offset: 1, color: C.aqua }
              ]
            },
            shadowColor: 'rgba(29, 111, 224, 0.28)',
            shadowBlur: 10,
            shadowOffsetY: 4
          },
          label: {
            show: true,
            position: 'top',
            formatter: (p) => `${p.value}%`,
            fontSize: 13,
            color: C.ink,
            fontWeight: 500
          }
        }
      ]
    },
    true
  )
}

/** 折线图：月度预约趋势（直线折线 + 纯色描点，无面积、无渐变） */
function renderTrend(list) {
  if (!list.length) {
    trendChart.setOption({ graphic: emptyGraphic('暂无预约趋势数据') }, true)
    return
  }
  trendChart.setOption(
    {
      ...CHART_ANIM,
      animationDelay: 80,
      tooltip: { trigger: 'axis' },
      grid: { left: 48, right: 24, top: 32, bottom: 40 },
      xAxis: {
        type: 'category',
        data: list.map((r) => r.month),
        axisLabel: { fontSize: 12, color: C.soft },
        axisLine: { lineStyle: { color: C.line } },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'value',
        name: '预约条数',
        minInterval: 1,
        nameTextStyle: { color: C.soft, fontSize: 12 },
        axisLabel: { fontSize: 12, color: C.soft },
        axisLine: { lineStyle: { color: C.line } },
        splitLine: { lineStyle: { color: C.line } }
      },
      series: [
        {
          type: 'line',
          data: list.map((r) => r.count),
          smooth: false,
          symbolSize: 6,
          itemStyle: { color: C.brand, borderWidth: 2, borderColor: '#ffffff' },
          lineStyle: { width: 3, color: C.brand },
          areaStyle: { color: 'rgba(29, 111, 224, 0.08)' },
          label: { show: true, position: 'top', fontSize: 13, color: C.ink, fontWeight: 500 }
        }
      ]
    },
    true
  )
}

/** 饼图：热门时段分布（占比口径与后端一致；品牌蓝 + 功能色轮转，无紫/霓虹） */
function renderTimeDistribution(list) {
  if (!list.length) {
    timeDistChart.setOption({ graphic: emptyGraphic('暂无时段分布数据') }, true)
    return
  }
  timeDistChart.setOption(
    {
      ...CHART_ANIM,
      animationDelay: 160,
      tooltip: {
        trigger: 'item',
        formatter: (p) =>
          `${escapeHtml(p.name)}<br/>${escapeHtml(p.value)} 条（${escapeHtml(p.percent)}%）`
      },
      legend: {
        bottom: 0,
        type: 'scroll',
        icon: 'circle',
        textStyle: { color: C.soft, fontSize: 13 }
      },
      color: PIE_PALETTE,
      series: [
        {
          type: 'pie',
          radius: ['38%', '66%'],
          center: ['50%', '46%'],
          itemStyle: { borderRadius: 6, borderColor: '#ffffff', borderWidth: 3 },
          label: { formatter: '{b}\n{d}%', fontSize: 13, color: C.ink },
          data: list.map((r) => ({ name: r.slot, value: r.count }))
        }
      ]
    },
    true
  )
}

/** ECharts 实例初始化（统一浅色主题，resize 跟随容器） */
function initCharts() {
  usageChart = echarts.init(usageRateEl.value)
  trendChart = echarts.init(trendEl.value)
  timeDistChart = echarts.init(timeDistEl.value)
  window.addEventListener('resize', handleResize)
}

function handleResize() {
  usageChart && usageChart.resize()
  trendChart && trendChart.resize()
  timeDistChart && timeDistChart.resize()
}

onMounted(() => {
  initCharts()
})

onBeforeUnmount(() => {
  loadSeq += 1
  window.removeEventListener('resize', handleResize)
  usageChart && usageChart.dispose()
  trendChart && trendChart.dispose()
  timeDistChart && timeDistChart.dispose()
  usageChart = trendChart = timeDistChart = null
})
</script>

<style scoped>
/* 页头右侧：快捷范围 + 日期区间 + 刷新，控件同高、可换行 */
.head-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-md);
  flex-wrap: wrap;
}

.dashboard-error {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-bottom: var(--space-lg);
  color: var(--warning);
  font-size: var(--text-sm);
}

/* 图表区：两列白面 + 32px 留白，窄屏堆叠 */
.chart-grid {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: var(--space-2xl);
  margin-bottom: var(--space-2xl);
}

.chart-title {
  margin: 0;
  font-size: var(--text-md);
  font-weight: 600;
  line-height: 1.3;
  color: var(--ink);
}

.chart-sub {
  margin: var(--space-xs) 0 var(--space-lg);
  font-size: var(--text-sm);
  line-height: 1.5;
  color: var(--ink-soft);
}

.chart-box {
  height: 380px;
}

.chart-box-tall {
  height: 320px;
}

@media (max-width: 1080px) {
  .chart-grid {
    grid-template-columns: 1fr;
  }
}

/* ---------- 第四轮（晨光玻璃）覆盖 ---------- */
.chart-card {
  background: var(--glass);
  -webkit-backdrop-filter: blur(14px) saturate(140%);
  backdrop-filter: blur(14px) saturate(140%);
  border: 1px solid var(--glass-line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-surface), var(--shadow-raised), var(--glass-sheen);
  padding: var(--space-xl);
}
.chart-title {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--ink);
}
</style>
