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
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Checked, DataAnalysis, School, Tickets, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const router = useRouter()
const userInfo = computed(() => userStore.userInfo)

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

/* 欢迎横幅（扁平深蓝，无渐变与光晕） */
.welcome-banner {
  background: var(--brand-primary);
  border-radius: var(--radius-lg);
  padding: 26px 30px;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.banner-title {
  font-size: 21px;
  font-weight: 700;
  margin-bottom: 8px;
}
.banner-tip {
  font-size: 13px;
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
  padding: 4px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.3);
  font-weight: 600;
  letter-spacing: 0.5px;
}
.chip-ok {
  background: rgba(46, 139, 87, 0.55);
}
.chip-off {
  background: rgba(179, 38, 30, 0.55);
}

/* 快捷入口 */
.quick-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.quick-card {
  background: #fff;
  border-radius: var(--radius-lg);
  border: 1px solid var(--border-color-light);
  box-shadow: var(--shadow-card);
  padding: 18px;
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}
.quick-card:hover {
  box-shadow: var(--shadow-hover);
  border-color: var(--border-color);
}

.quick-icon {
  width: 44px;
  height: 44px;
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
  font-weight: 600;
  color: var(--text-primary);
  display: block;
  margin-bottom: 3px;
}
.quick-desc {
  font-size: 12px;
  color: var(--text-secondary);
}

/* 账号信息 */
.info-card {
  border-radius: var(--radius-lg);
}
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 14px;
}
.info-table :deep(.el-descriptions__label) {
  font-weight: 600;
}
</style>
