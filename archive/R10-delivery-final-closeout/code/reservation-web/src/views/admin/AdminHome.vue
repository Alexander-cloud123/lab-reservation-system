<template>
  <el-container class="admin-layout">
    <!-- 顶部栏 -->
    <el-header class="admin-header">
      <div class="header-left">
        <div class="logo-badge">
          <el-icon :size="16"><OfficeBuilding /></el-icon>
        </div>
        <span class="header-logo">高校实验室预约管理系统</span>
        <span class="role-chip">管理端</span>
      </div>
      <div class="header-right">
        <el-dropdown trigger="click" @command="handleUserCommand">
          <div class="user-entry">
            <el-avatar :size="30" class="user-avatar">{{ avatarText }}</el-avatar>
            <span class="user-name">{{ userInfo ? userInfo.name : '-' }}</span>
            <el-icon class="user-caret"><CaretBottom /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>
                <span class="user-account">账号：{{ userInfo ? userInfo.username : '-' }}</span>
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <el-container class="admin-body">
      <!-- 侧边导航菜单（管理端入口） -->
      <el-aside width="216px" class="admin-aside">
        <el-menu :default-active="activeMenu" router class="admin-menu">
          <el-menu-item index="/admin/home">
            <el-icon><HomeFilled /></el-icon>
            <span>首页</span>
          </el-menu-item>
          <el-menu-item index="/admin/users">
            <el-icon><User /></el-icon>
            <span>用户管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/classrooms">
            <el-icon><School /></el-icon>
            <span>教室资源管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/audits">
            <el-icon><Checked /></el-icon>
            <span>预约审核</span>
          </el-menu-item>
          <el-menu-item index="/admin/records">
            <el-icon><Tickets /></el-icon>
            <span>预约记录</span>
          </el-menu-item>
          <el-menu-item index="/admin/dashboard">
            <el-icon><DataAnalysis /></el-icon>
            <span>数据看板</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <!-- 主内容区 -->
      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  CaretBottom,
  Checked,
  DataAnalysis,
  HomeFilled,
  OfficeBuilding,
  School,
  SwitchButton,
  Tickets,
  User
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const userInfo = computed(() => userStore.userInfo)

/** 头像占位：取姓名末位，多字取后两字 */
const avatarText = computed(() => {
  const name = userInfo.value ? userInfo.value.name : ''
  return name ? name.slice(-2) : '管'
})

/** 侧边菜单高亮：跟随当前路由 */
const activeMenu = computed(() => route.path)

onMounted(() => {
  // 拉取最新用户信息，验证 Token 有效性（失效时 request 拦截器自动跳登录页）
  userStore.fetchInfo().catch(() => {})
})

/** 用户下拉菜单指令 */
function handleUserCommand(cmd) {
  if (cmd === 'logout') {
    handleLogout()
  }
}

/** 退出登录（二次确认） */
async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await userStore.logout()
    router.push('/login')
  } catch {
    // 用户取消
  }
}
</script>

<style scoped>
.admin-layout {
  min-height: 100vh;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 24px;
  background: var(--bg-card);
  border-bottom: 1px solid var(--border-color-light);
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.logo-badge {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-md);
  background: var(--brand-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.header-logo {
  font-size: 16px;
  font-weight: 500;
  line-height: 24px;
  color: var(--text-primary);
}

.role-chip {
  font-size: 12px;
  line-height: 18px;
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  font-weight: 400;
}

.header-right {
  display: flex;
  align-items: center;
}

.user-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-md);
  transition: background-color 0.15s;
}
.user-entry:hover {
  background: var(--bg-page);
}
.user-avatar {
  background: var(--brand-primary);
  color: #fff;
  font-size: 12px;
  font-weight: 500;
}
.user-name {
  font-size: 14px;
  font-weight: 400;
  color: var(--text-regular);
}
.user-caret {
  font-size: 12px;
  color: var(--text-placeholder);
}
.user-account {
  font-size: 12px;
  line-height: 20px;
  color: var(--text-placeholder);
}

.admin-body {
  height: calc(100vh - 56px);
}

/* 侧栏：飞书工作台式浅灰面板（选中项蓝底蓝字） */
.admin-aside {
  background: var(--bg-page);
  border-right: 1px solid var(--border-color-light);
  padding: 8px;
}

.admin-menu {
  border-right: none;
  background: transparent;
}
.admin-menu .el-menu-item {
  height: 36px;
  line-height: 36px;
  padding: 0 8px;
  border-radius: var(--radius-md);
  margin-bottom: 2px;
  font-size: 14px;
  color: var(--text-regular);
  font-weight: 400;
}
.admin-menu .el-menu-item .el-icon {
  font-size: 16px;
  color: var(--text-regular);
  margin-right: 8px;
}
.admin-menu .el-menu-item:hover {
  background: rgba(31, 35, 41, 0.05);
  color: var(--text-primary);
}
.admin-menu .el-menu-item:hover .el-icon {
  color: var(--text-primary);
}
.admin-menu .el-menu-item.is-active {
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  font-weight: 500;
}
.admin-menu .el-menu-item.is-active .el-icon {
  color: var(--brand-primary);
}

.admin-main {
  background: var(--bg-page);
  padding: 24px;
  overflow-y: auto;
}
</style>
