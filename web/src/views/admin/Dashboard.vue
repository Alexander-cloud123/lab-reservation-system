<template>
  <div class="dashboard-page">
    <!-- 顶部：标题 + 时间范围筛选 -->
    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <span class="page-title">数据看板</span>
        <el-radio-group v-model="quickRange" size="small" @change="handleQuickRange">
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
        <el-button type="primary" size="small" :loading="loading" @click="loadAll">刷新</el-button>
      </div>
    </el-card>

    <!-- 三图表：使用率排行柱状图 / 月度趋势折线图 / 热门时段饼图 -->
    <el-row :gutter="16">
      <el-col :span="14">
        <el-card shadow="never" class="chart-card">
          <div class="chart-title">教室使用率排行</div>
          <div class="chart-sub">已通过预约占用小时 / 区间可预约小时</div>
          <div ref="usageRateEl" class="chart-box" />
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never" class="chart-card">
          <div class="chart-title">热门时段分布</div>
          <div class="chart-sub">已通过预约按开始时段</div>
          <div ref="timeDistEl" class="chart-box" />
        </el-card>
      </el-col>
    </el-row>
    <el-card shadow="never" class="chart-card">
      <div class="chart-title">月度预约趋势</div>
      <div class="chart-sub">全部状态预约条数</div>
      <div ref="trendEl" class="chart-box chart-box-tall" />
    </el-card>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
// ECharts 按需引入（R6 联调优化：避免全量包约 1MB 打入 Dashboard chunk，依赖版本不变）
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent, GraphicComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import dayjs from 'dayjs'
import { getUsageRate, getTrend, getTimeDistribution } from '@/api/stats'

// 注册看板用到的图表类型与组件（柱状图/折线图/饼图 + 提示框/网格/图例 + 空数据提示 graphic 文本 + Canvas 渲染）
echarts.use([BarChart, LineChart, PieChart, TooltipComponent, GridComponent, LegendComponent, GraphicComponent, CanvasRenderer])

const loading = ref(false)
const quickRange = ref('30')
const dateRange = ref([dayjs().subtract(29, 'day').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')])

const usageRateEl = ref(null)
const trendEl = ref(null)
const timeDistEl = ref(null)

let usageChart = null
let trendChart = null
let timeDistChart = null

/** 当前区间参数（YYYY-MM-DD） */
function rangeParams() {
  const [start, end] = dateRange.value || []
  return start && end ? { startDate: start, endDate: end } : {}
}

/** 快捷范围选择：近 7/30/90 天、本年 */
function handleQuickRange(key) {
  const today = dayjs()
  if (key === 'year') {
    dateRange.value = [today.startOf('year').format('YYYY-MM-DD'), today.format('YYYY-MM-DD')]
  } else {
    const days = Number(key)
    dateRange.value = [today.subtract(days - 1, 'day').format('YYYY-MM-DD'), today.format('YYYY-MM-DD')]
  }
  loadAll()
}

/** 自定义日期范围变化 */
function handleDateRangeChange() {
  if (dateRange.value && dateRange.value.length === 2) {
    quickRange.value = ''
    loadAll()
  }
}

/** 三图数据均来自后端只读统计接口（禁止前端伪造），时间筛选统一作用三图 */
async function loadAll() {
  loading.value = true
  try {
    const params = rangeParams()
    const [usageRes, trendRes, distRes] = await Promise.all([
      getUsageRate(params),
      getTrend(params),
      getTimeDistribution(params)
    ])
    renderUsageRate(usageRes.data || [])
    renderTrend(trendRes.data || [])
    renderTimeDistribution(distRes.data || [])
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  } finally {
    loading.value = false
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
        fill: '#8b98a8' // 对齐 --text-placeholder Token
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
  const names = list.map((r) => r.name)
  const values = list.map((r) => r.usageRate)
  usageChart.setOption(
    {
      tooltip: {
        trigger: 'axis',
        formatter(params) {
          const p = params[0]
          const item = list[p.dataIndex]
          if (!item) {
            return ''
          }
          return `${item.name}（${item.roomNo}）<br/>使用率：${item.usageRate}%<br/>占用时长：${item.approvedHours} 小时`
        }
      },
      grid: { left: 48, right: 24, top: 36, bottom: 80 },
      xAxis: {
        type: 'category',
        data: names,
        axisLabel: { rotate: 35, fontSize: 11 },
        axisLine: { lineStyle: { color: '#d8dee6' } },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'value',
        name: '使用率（%）',
        nameTextStyle: { color: '#8b98a8' },
        axisLabel: { formatter: '{value}%', color: '#64748b' },
        splitLine: { lineStyle: { color: '#e7ebf0' } }
      },
      series: [
        {
          type: 'bar',
          data: values,
          barMaxWidth: 34,
          itemStyle: {
            borderRadius: [6, 6, 0, 0],
            color: '#1e6091'
          },
          label: {
            show: true,
            position: 'top',
            formatter: (p) => `${p.value}%`,
            fontSize: 11,
            color: '#3d4a5a',
            fontWeight: 600
          }
        }
      ]
    },
    true
  )
}

/** 折线图：月度预约趋势（主色线 + 同色系低透明面积，无渐变） */
function renderTrend(list) {
  if (!list.length) {
    trendChart.setOption({ graphic: emptyGraphic('暂无预约趋势数据') }, true)
    return
  }
  trendChart.setOption(
    {
      tooltip: { trigger: 'axis' },
      grid: { left: 48, right: 24, top: 36, bottom: 40 },
      xAxis: {
        type: 'category',
        data: list.map((r) => r.month),
        axisLine: { lineStyle: { color: '#d8dee6' } },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'value',
        name: '预约条数',
        minInterval: 1,
        nameTextStyle: { color: '#8b98a8' },
        axisLabel: { color: '#64748b' },
        splitLine: { lineStyle: { color: '#e7ebf0' } }
      },
      series: [
        {
          type: 'line',
          data: list.map((r) => r.count),
          smooth: true,
          symbolSize: 8,
          itemStyle: { color: '#1e6091' },
          lineStyle: { width: 3, color: '#1e6091' },
          areaStyle: {
            color: 'rgba(30, 96, 145, 0.10)'
          },
          label: { show: true, position: 'top', fontSize: 11, color: '#3d4a5a', fontWeight: 600 }
        }
      ]
    },
    true
  )
}

/** 饼图：热门时段分布（占比口径与后端一致；承诺调色板，无紫/青/霓虹） */
function renderTimeDistribution(list) {
  if (!list.length) {
    timeDistChart.setOption({ graphic: emptyGraphic('暂无时段分布数据') }, true)
    return
  }
  const PALETTE = ['#1e6091', '#2e8b57', '#b45309', '#b3261e', '#0f766e', '#64748b', '#6d4a2f']
  timeDistChart.setOption(
    {
      tooltip: {
        trigger: 'item',
        formatter: (p) => `${p.name}<br/>${p.value} 条（${p.percent}%）`
      },
      legend: { bottom: 0, type: 'scroll', fontSize: 11, textStyle: { color: '#64748b' } },
      color: PALETTE,
      series: [
        {
          type: 'pie',
          radius: ['38%', '66%'],
          center: ['50%', '46%'],
          itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
          label: { formatter: '{b}\n{d}%', fontSize: 11, color: '#3d4a5a' },
          data: list.map((r) => ({ name: r.slot, value: r.count }))
        }
      ]
    },
    true
  )
}

/** ECharts 实例初始化（统一深色无特殊主题，resize 跟随容器） */
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
  loadAll()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  usageChart && usageChart.dispose()
  trendChart && trendChart.dispose()
  timeDistChart && timeDistChart.dispose()
  usageChart = trendChart = timeDistChart = null
})
</script>

<style scoped>
.dashboard-page {
  max-width: 1200px;
  margin: 0 auto;
}

.filter-card {
  margin-bottom: 16px;
  border-radius: var(--radius-lg);
}

.filter-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.page-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
  margin-right: 8px;
}

.chart-card {
  margin-bottom: 16px;
  border-radius: var(--radius-lg);
}

.chart-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary);
}

.chart-sub {
  font-size: 12px;
  color: var(--text-placeholder);
  margin: 2px 0 10px;
}

.chart-box {
  height: 380px;
}

.chart-box-tall {
  height: 320px;
}
</style>
