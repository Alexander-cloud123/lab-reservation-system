<template>
  <div class="welcome-wrap page-container">
    <!-- 页头：问候 + 说明 + 账号状态 -->
    <div class="page-head">
      <div>
        <h1 class="page-title">你好，{{ userInfo ? userInfo.name : '-' }}</h1>
        <p class="page-sub">欢迎使用高校实验室预约管理系统管理端，今日概览如下</p>
      </div>
      <span class="status-pill" :class="userInfo && userInfo.status === 1 ? 'is-approved' : 'is-rejected'">
        {{ userInfo && userInfo.status === 1 ? '账号正常' : '已禁用' }}
      </span>
    </div>

    <!-- 精简时刻带：管理端也看得到现在处在一天的什么位置 -->
    <TimeBand class="welcome-band reveal" compact :summary="bandSummary" />

    <div v-if="loadError" class="overview-error" role="status">
      <span>概览加载失败，指标暂不可用。</span>
      <el-button link type="primary" @click="loadOverview">重试</el-button>
    </div>

    <!-- 今日概览：一张白面，四列数据 -->
    <div class="surface stat-band reveal stagger" style="--i: 1">
      <div class="stat-item">
        <div class="stat-label">今日预约</div>
        <div class="stat-value">{{ todayDisplay }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">待审核</div>
        <div class="stat-value" :class="{ 'is-attention': !loadError && pendingMetric > 0 }">
          {{ pendingDisplay }}
        </div>
      </div>
      <div class="stat-item">
        <div class="stat-label">教室总数</div>
        <div class="stat-value">{{ classroomDisplay }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">用户总数</div>
        <div class="stat-value">{{ userDisplay }}</div>
      </div>
    </div>

    <!-- 管理入口：五行大按钮，hover 浅底 -->
    <div class="surface quick-surface">
      <div class="section-title">
        <span>管理入口</span>
        <span class="section-note">也可从左侧菜单进入</span>
      </div>
      <div class="quick-grid">
        <div class="quick-card reveal stagger" style="--i: 2" v-clickable @click="go('/admin/users')">
          <span class="quick-name">用户管理</span>
          <span class="quick-desc">查看与维护系统用户</span>
        </div>
        <div class="quick-card reveal stagger" style="--i: 3" v-clickable @click="go('/admin/classrooms')">
          <span class="quick-name">教室资源管理</span>
          <span class="quick-desc">维护教室与资源信息</span>
        </div>
        <div class="quick-card reveal stagger" style="--i: 4" v-clickable @click="go('/admin/audits')">
          <span class="quick-name">预约审核</span>
          <span class="quick-desc">处理待审核预约申请</span>
        </div>
        <div class="quick-card reveal stagger" style="--i: 5" v-clickable @click="go('/admin/records')">
          <span class="quick-name">预约记录</span>
          <span class="quick-desc">查询全量预约与导出</span>
        </div>
        <div class="quick-card reveal stagger" style="--i: 6" v-clickable @click="go('/admin/dashboard')">
          <span class="quick-name">数据看板</span>
          <span class="quick-desc">可视化统计与趋势分析</span>
        </div>
      </div>
    </div>

    <!-- 账号信息 -->
    <div class="surface">
      <div class="section-title">
        <span>账号信息</span>
      </div>
      <dl class="info-list">
        <div class="info-row">
          <dt>登录账号</dt>
          <dd>{{ userInfo ? userInfo.username : '-' }}</dd>
        </div>
        <div class="info-row">
          <dt>姓名</dt>
          <dd>{{ userInfo ? userInfo.name : '-' }}</dd>
        </div>
        <div class="info-row">
          <dt>角色</dt>
          <dd>管理员</dd>
        </div>
        <div class="info-row">
          <dt>账号状态</dt>
          <dd>{{ userInfo && userInfo.status === 1 ? '正常' : '禁用' }}</dd>
        </div>
      </dl>
    </div>
  </div>
</template>
<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getOverview } from '@/api/stats'
import TimeBand from '@/components/common/TimeBand.vue'
import { useCountUp } from '@/composables/useCountUp'

const userStore = useUserStore()
const router = useRouter()
const userInfo = computed(() => userStore.userInfo)

/** 首页数据概览；尚未返回或字段未知时显示 —，接口明确返回 0 时才显示真实 0。 */
const overview = ref({})
const loadError = ref(false)

/** 概览失败不阻断快捷入口，只标记指标未知并保留重试入口。 */
async function loadOverview() {
  loadError.value = false
  try {
    const res = await getOverview()
    overview.value = res.data || {}
    loadError.value = false
  } catch {
    loadError.value = true
  }
}

onMounted(loadOverview)

/** 快捷入口跳转 */
function readMetric(key) {
  const value = overview.value[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

const todayMetric = computed(() => readMetric('todayReservationCount'))
const pendingMetric = computed(() => readMetric('pendingAuditCount'))
const classroomMetric = computed(() => readMetric('classroomCount'))
const userMetric = computed(() => readMetric('userCount'))

/** 概览数字：已知指标保留入场动画，加载失败或字段未知时统一显示 —。 */
const todayCount = useCountUp(computed(() => todayMetric.value ?? 0))
const pendingCount = useCountUp(computed(() => pendingMetric.value ?? 0))
const classroomCount = useCountUp(computed(() => classroomMetric.value ?? 0))
const userCount = useCountUp(computed(() => userMetric.value ?? 0))
const todayDisplay = computed(() => (loadError.value || todayMetric.value === null ? '—' : todayCount.value))
const pendingDisplay = computed(() => (loadError.value || pendingMetric.value === null ? '—' : pendingCount.value))
const classroomDisplay = computed(() => (loadError.value || classroomMetric.value === null ? '—' : classroomCount.value))
const userDisplay = computed(() => (loadError.value || userMetric.value === null ? '—' : userCount.value))

/** 时刻带摘要：今天的预约与待审核量 */
const bandSummary = computed(() => {
  if (loadError.value || todayMetric.value === null) {
    return '今日预约数据暂不可用'
  }
  const parts = [`今天有 ${todayMetric.value} 个预约`]
  if (pendingMetric.value > 0) {
    parts.push(`${pendingMetric.value} 个在等审核`)
  }
  return parts.join('，')
})
function go(path) {
  router.push(path)
}
</script>

<style scoped>
.welcome-wrap {
  max-width: 1200px;
}

/* 今日概览：四列数据，列间靠留白分隔 */
.welcome-band {
  margin-bottom: var(--space-xl);
}
.overview-error {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
  color: var(--warning);
  font-size: var(--text-sm);
}
.stat-band {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-2xl);
  padding: var(--space-xl) var(--space-2xl);
}
.stat-item {
  min-width: 0;
}
.stat-label {
  font-size: var(--text-sm);
  line-height: 1.5;
  color: var(--ink-soft);
  margin-bottom: 8px;
}
.stat-value {
  font-size: var(--text-xl);
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: -0.01em;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.stat-value.is-attention {
  color: var(--attention);
}

/* 管理入口：整行可点，56px 高 */
.quick-surface {
  margin-top: var(--space-xl);
  padding: var(--space-lg) var(--space-xs) var(--space-sm);
}
.quick-surface .section-title {
  padding: 0 var(--space-lg);
  margin-bottom: var(--space-sm);
}
.quick-grid {
  display: flex;
  flex-direction: column;
}
.quick-card {
  display: flex;
  align-items: center;
  gap: var(--space-lg);
  height: 56px;
  padding: 0 var(--space-lg);
  border-radius: var(--radius-control);
  cursor: pointer;
  transition: background-color 0.16s ease;
}
.quick-card:hover {
  background: var(--brand-soft);
}
.quick-name {
  flex: none;
  width: 132px;
  font-size: var(--text-base);
  font-weight: 600;
  line-height: 1.5;
  color: var(--ink);
}
.quick-card:hover .quick-name {
  color: var(--brand);
}
.quick-desc {
  font-size: var(--text-sm);
  line-height: 1.5;
  color: var(--ink-soft);
}

/* 账号信息：两列定义列表 */
.info-list {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--space-2xl);
}
.info-row {
  display: flex;
  align-items: baseline;
  gap: var(--space-lg);
  padding: 12px 0;
  border-bottom: 1px solid var(--line);
}
.info-row:last-child,
.info-row:nth-last-child(2):nth-child(odd) {
  border-bottom: 0;
}
.info-row dt {
  flex: none;
  width: 96px;
  font-size: var(--text-sm);
  line-height: 1.5;
  color: var(--ink-soft);
}
.info-row dd {
  margin: 0;
  font-size: var(--text-base);
  line-height: 1.5;
  color: var(--ink);
  font-weight: 500;
}

@media (max-width: 900px) {
  .stat-band {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--space-xl);
  }
  .info-list {
    grid-template-columns: 1fr;
  }
  .info-row:nth-last-child(2) {
    border-bottom: 1px solid var(--line);
  }
}

/* ---------- 第四轮（晨光玻璃）覆盖 ---------- */
.welcome-band {
  margin-bottom: var(--space-xl);
}
/* 不等宽 bento：第一格占 1.6 份，数字用渐变大字（唯一允许的「大字」场合之一） */
.stat-band {
  grid-template-columns: 1.6fr 1fr 1fr 1fr;
  gap: var(--space-xl);
  padding: var(--space-xl) var(--space-2xl);
  background: var(--glass-strong);
}
.stat-item {
  position: relative;
  padding-left: var(--space-lg);
}
.stat-item::before {
  content: '';
  position: absolute;
  left: 0;
  top: 2px;
  bottom: 2px;
  width: 3px;
  border-radius: var(--radius-sm);
  background: var(--grad);
  opacity: 0.9;
}
.stat-item:first-child::before {
  background: var(--grad-sun);
}
.stat-value {
  font-family: var(--font-num);
  font-size: var(--text-xl);
  font-weight: 600;
  letter-spacing: -0.02em;
  color: var(--deep);
}
.stat-item:first-child .stat-value {
  font-size: var(--text-display);
  line-height: 1;
}
.stat-value.is-attention {
  color: var(--attention);
}
.quick-surface {
  background: var(--glass);
}
.quick-card {
  height: 64px;
  border-radius: var(--radius-control);
  transition: background-color var(--dur-1) var(--ease-out), transform var(--dur-1) var(--ease-out);
}
.quick-card:hover {
  background: rgba(255, 255, 255, 0.72);
  transform: translateX(2px);
}
.quick-card:hover .quick-name {
  color: var(--brand);
}
.quick-card::after {
  content: '';
  width: 6px;
  height: 6px;
  margin-left: auto;
  border-radius: 50%;
  background: var(--sky);
  opacity: 0;
  transition: opacity var(--dur-1) var(--ease-out);
}
.quick-card:hover::after {
  opacity: 1;
}
.info-row {
  border-bottom: 1px solid var(--line);
}
</style>