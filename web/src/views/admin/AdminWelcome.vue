<template>
  <div class="welcome-wrap">
    <!-- 页头：问候 + 今日概览（一行事实，不做大色块横幅） -->
    <div class="page-head">
      <div>
        <div class="page-title">你好，{{ userInfo ? userInfo.name : '-' }}</div>
        <div class="page-sub">欢迎使用高校实验室预约管理系统管理端，今日概览如下</div>
      </div>
      <div class="head-facts">
        <span class="head-status">
          <span class="status-dot" :class="userInfo && userInfo.status === 1 ? 'is-approved' : 'is-rejected'" />
          {{ userInfo && userInfo.status === 1 ? '账号正常' : '已禁用' }}
        </span>
      </div>
    </div>

    <!-- 核心数据：排课表式的标头列（标签在上、数字在下），4 列细线分隔 -->
    <div class="stat-grid">
      <div class="stat-item">
        <div class="stat-label">今日预约</div>
        <div class="stat-value">{{ overview.todayReservationCount ?? 0 }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">待审核</div>
        <div class="stat-value">{{ overview.pendingAuditCount ?? 0 }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">教室总数</div>
        <div class="stat-value">{{ overview.classroomCount ?? 0 }}</div>
      </div>
      <div class="stat-item">
        <div class="stat-label">用户总数</div>
        <div class="stat-value">{{ overview.userCount ?? 0 }}</div>
      </div>
    </div>

    <!-- 快捷入口：列表行（标题 + 说明），不用图标方块卡片 -->
    <div class="section">
      <div class="section-title">
        <span>管理入口</span>
        <span class="section-note">按左侧菜单也可进入</span>
      </div>
      <div class="quick-grid">
        <div class="quick-card" v-clickable @click="go('/admin/users')">
          <span class="quick-name">用户管理</span>
          <span class="quick-desc">查看与维护系统用户</span>
        </div>
        <div class="quick-card" v-clickable @click="go('/admin/classrooms')">
          <span class="quick-name">教室资源管理</span>
          <span class="quick-desc">维护教室与资源信息</span>
        </div>
        <div class="quick-card" v-clickable @click="go('/admin/audits')">
          <span class="quick-name">预约审核</span>
          <span class="quick-desc">处理待审核预约申请</span>
        </div>
        <div class="quick-card" v-clickable @click="go('/admin/records')">
          <span class="quick-name">预约记录</span>
          <span class="quick-desc">查询全量预约与导出</span>
        </div>
        <div class="quick-card" v-clickable @click="go('/admin/dashboard')">
          <span class="quick-name">数据看板</span>
          <span class="quick-desc">可视化统计与趋势分析</span>
        </div>
      </div>
    </div>

    <!-- 账号信息：定义列表，不用边框表格 -->
    <div class="section">
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
          <dd>
            <span class="status-dot" :class="userInfo && userInfo.status === 1 ? 'is-approved' : 'is-rejected'" />
            {{ userInfo && userInfo.status === 1 ? '正常' : '禁用' }}
          </dd>
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

const userStore = useUserStore()
const router = useRouter()
const userInfo = computed(() => userStore.userInfo)

/** 首页数据概览（今日预约/待审核/教室总数/用户总数），初始为空由模板兜底显示 0 */
const overview = ref({})

/**
 * 加载数据概览；失败静默——概览属展示性数据，加载失败不应影响管理端首页的快捷入口使用
 */
async function loadOverview() {
  try {
    const res = await getOverview()
    overview.value = res.data || {}
  } catch {
    // 静默降级：数字保持 0，快捷入口照常可用
  }
}

onMounted(loadOverview)

/** 快捷入口跳转 */
function go(path) {
  router.push(path)
}
</script>

<style scoped>
.welcome-wrap {
  max-width: 1080px;
  margin: 0 auto;
  padding-bottom: 24px;
}

.head-facts {
  display: flex;
  align-items: center;
  gap: 16px;
}
.head-status {
  font-size: 12px;
  line-height: 20px;
  color: var(--text-regular);
}

/* 数据列：像课表表头——标签在上、数字在下，列与列之间用细线 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-top: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);
}
.stat-item {
  padding: 16px 20px 18px 0;
  border-left: 1px solid var(--rule);
}
.stat-item:first-child {
  border-left: 0;
  padding-left: 0;
}
.stat-label {
  font-size: 12px;
  line-height: 20px;
  color: var(--text-regular);
  margin-bottom: 4px;
}
.stat-value {
  font-family: var(--font-num);
  font-size: 26px;
  font-weight: 500;
  line-height: 32px;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

/* 快捷入口：列表行，hover 出现下划线，不做卡片 */
.quick-grid {
  display: flex;
  flex-direction: column;
}
.quick-card {
  display: flex;
  align-items: baseline;
  gap: 16px;
  padding: 10px 0;
  border-bottom: 1px solid var(--rule);
  cursor: pointer;
}
.quick-card:last-child {
  border-bottom: 0;
}
.quick-card:hover {
  background: var(--bg-subtle);
}
.quick-name {
  flex: none;
  width: 120px;
  font-size: 14px;
  font-weight: 500;
  line-height: 22px;
  color: var(--text-primary);
}
.quick-card:hover .quick-name {
  text-decoration: underline;
  text-underline-offset: 3px;
}
.quick-desc {
  font-size: 13px;
  line-height: 22px;
  color: var(--text-regular);
}

/* 账号信息：定义列表，标签固定宽 */
.info-list {
  margin: 0;
}
.info-row {
  display: flex;
  align-items: baseline;
  padding: 8px 0;
  border-bottom: 1px solid var(--rule);
}
.info-row:last-child {
  border-bottom: 0;
}
.info-row dt {
  flex: none;
  width: 120px;
  font-size: 13px;
  line-height: 20px;
  color: var(--text-placeholder);
}
.info-row dd {
  margin: 0;
  font-size: 14px;
  line-height: 22px;
  color: var(--text-primary);
}

@media (max-width: 720px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .stat-item {
    border-left: 0;
  }
  .quick-card {
    flex-direction: column;
    gap: 2px;
  }
}
</style>
