<template>
  <div class="welcome-wrap">
    <!-- 欢迎横幅（扁平深蓝面板，无渐变/光晕） -->
    <div class="welcome-banner">
      <div class="banner-info">
        <h3 class="banner-title">你好，{{ userInfo ? userInfo.name : '-' }}</h3>
        <p class="banner-tip">
          欢迎使用高校实验室预约管理系统管理端，今日也可通过左侧菜单使用全部管理模块。
        </p>
      </div>
      <div class="banner-badge">
        <span class="badge-chip">管理员</span>
        <span class="badge-chip" :class="userInfo && userInfo.status === 1 ? 'chip-ok' : 'chip-off'">
          {{ userInfo && userInfo.status === 1 ? '账号正常' : '已禁用' }}
        </span>
      </div>
    </div>

    <!-- 核心数据概览（需求文档 1.3 管理员端第 8 页：今日预约 / 待审核 / 教室总数 / 用户总数） -->
    <div class="stat-grid">
      <div class="stat-item">
        <div class="stat-value">{{ overview.todayReservationCount ?? 0 }}</div>
        <div class="stat-label">今日预约</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ overview.pendingAuditCount ?? 0 }}</div>
        <div class="stat-label">待审核</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ overview.classroomCount ?? 0 }}</div>
        <div class="stat-label">教室总数</div>
      </div>
      <div class="stat-item">
        <div class="stat-value">{{ overview.userCount ?? 0 }}</div>
        <div class="stat-label">用户总数</div>
      </div>
    </div>

    <!-- 快捷功能入口 -->
    <div class="quick-grid">
      <div class="quick-card" @click="go('/admin/users')">
        <div class="quick-icon"><el-icon :size="22"><User /></el-icon></div>
        <div class="quick-info">
          <span class="quick-name">用户管理</span>
          <span class="quick-desc">查看与维护系统用户</span>
        </div>
      </div>
      <div class="quick-card" @click="go('/admin/classrooms')">
        <div class="quick-icon"><el-icon :size="22"><School /></el-icon></div>
        <div class="quick-info">
          <span class="quick-name">教室资源管理</span>
          <span class="quick-desc">维护教室与资源信息</span>
        </div>
      </div>
      <div class="quick-card" @click="go('/admin/audits')">
        <div class="quick-icon"><el-icon :size="22"><Checked /></el-icon></div>
        <div class="quick-info">
          <span class="quick-name">预约审核</span>
          <span class="quick-desc">处理待审核预约申请</span>
        </div>
      </div>
      <div class="quick-card" @click="go('/admin/records')">
        <div class="quick-icon"><el-icon :size="22"><Tickets /></el-icon></div>
        <div class="quick-info">
          <span class="quick-name">预约记录</span>
          <span class="quick-desc">查询全量预约与导出</span>
        </div>
      </div>
      <div class="quick-card" @click="go('/admin/dashboard')">
        <div class="quick-icon"><el-icon :size="22"><DataAnalysis /></el-icon></div>
        <div class="quick-info">
          <span class="quick-name">数据看板</span>
          <span class="quick-desc">可视化统计与趋势分析</span>
        </div>
      </div>
    </div>

    <!-- 账号信息卡 -->
    <el-card shadow="never" class="info-card">
      <div class="section-title">账号信息</div>
      <el-descriptions :column="2" border class="info-table">
        <el-descriptions-item label="登录账号">{{ userInfo ? userInfo.username : '-' }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ userInfo ? userInfo.name : '-' }}</el-descriptions-item>
        <el-descriptions-item label="角色">
          <el-tag type="warning" effect="light" round size="small">管理员</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="账号状态">
          <el-tag :type="userInfo && userInfo.status === 1 ? 'success' : 'danger'" effect="light" round size="small">
            {{ userInfo && userInfo.status === 1 ? '正常' : '禁用' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Checked, DataAnalysis, School, Tickets, User } from '@element-plus/icons-vue'
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
    // 静默降级：卡片保持 0，快捷入口照常可用
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
}

/* 欢迎横幅（品牌蓝实底，1px 内描边替代投影） */
.welcome-banner {
  background: var(--brand-primary);
  border-radius: var(--radius-lg);
  padding: 24px 32px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.banner-title {
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
  margin-bottom: 8px;
}
.banner-tip {
  font-size: 12px;
  line-height: 20px;
  color: rgba(255, 255, 255, 0.85);
  max-width: 560px;
}
.banner-badge {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.badge-chip {
  font-size: 12px;
  line-height: 20px;
  padding: 4px 12px;
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.3);
  font-weight: 500;
}
.chip-ok,
.chip-off {
  border-color: transparent;
  color: #fff;
}
.chip-ok {
  background: rgba(26, 117, 38, 0.92);
}
.chip-off {
  background: rgba(192, 42, 38, 0.92);
}

/* 核心数据概览（白底 + 1px 描边，无投影） */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.stat-item {
  background: var(--bg-card);
  border-radius: var(--radius-lg);
  border: 1px solid var(--border-color-light);
  padding: 16px;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: var(--text-primary);
  line-height: 28px;
  font-variant-numeric: tabular-nums;
}

.stat-label {
  font-size: 12px;
  line-height: 20px;
  color: var(--text-regular);
  margin-top: 4px;
}

/* 快捷入口 */
.quick-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.quick-card {
  background: var(--bg-card);
  border-radius: var(--radius-lg);
  border: 1px solid var(--border-color-light);
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}
.quick-card:hover {
  background: var(--bg-subtle);
  border-color: var(--border-color);
}

.quick-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.quick-info {
  flex: 1;
  min-width: 0;
}
.quick-name {
  font-size: 14px;
  line-height: 22px;
  font-weight: 500;
  color: var(--text-primary);
  display: block;
  margin-bottom: 4px;
}
.quick-desc {
  font-size: 12px;
  line-height: 20px;
  color: var(--text-regular);
}

/* 账号信息 */
.info-card {
  border-radius: var(--radius-lg);
}
.section-title {
  font-size: 14px;
  line-height: 22px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 12px;
}
.info-table :deep(.el-descriptions__label) {
  font-weight: 500;
}
</style>
