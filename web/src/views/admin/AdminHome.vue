<template>
  <el-container class="admin-layout">
    <!-- 顶部栏 -->
    <el-header class="admin-header">
      <div class="header-left">
        <div class="logo-badge" aria-hidden="true">预约</div>
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
            <span>首页</span>
          </el-menu-item>
          <el-menu-item index="/admin/users">
            <span>用户管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/classrooms">
            <span>教室资源管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/audits">
            <span>预约审核</span>
          </el-menu-item>
          <el-menu-item index="/admin/records">
            <span>预约记录</span>
          </el-menu-item>
          <el-menu-item index="/admin/dashboard">
            <span>数据看板</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <!-- 主内容区 -->
      <el-main class="admin-main">
        <router-view v-slot="{ Component, route: currentRoute }">
          <transition name="page-swap" mode="out-in">
            <component :is="Component" :key="currentRoute.path" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { CaretBottom, SwitchButton } from '@element-plus/icons-vue'
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

/* 顶栏：玻璃贴顶（四类玻璃容器之一） */
.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  padding: 0 32px;
  background: var(--glass);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  border-bottom: 1px solid var(--glass-line);
  box-shadow: var(--shadow-surface), var(--glass-sheen);
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo-badge {
  height: 28px;
  padding: 0 11px;
  border-radius: var(--radius-sm);
  background: var(--grad);
  color: #fff;
  box-shadow: 0 4px 14px rgba(29, 111, 224, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-family-sans);
  font-size: var(--text-sm);
  font-weight: 600;
  letter-spacing: 0.04em;
}

.header-logo {
  font-size: var(--text-md);
  font-weight: 600;
  line-height: 24px;
  color: var(--ink);
  letter-spacing: -0.01em;
}

.role-chip {
  padding-left: 10px;
  border-left: 1px solid var(--line-strong);
  font-size: var(--text-xs);
  line-height: 18px;
  color: var(--ink-soft);
  font-weight: 500;
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
  padding: 4px 10px;
  border-radius: var(--radius-control);
  transition: background-color var(--dur-1) var(--ease-out);
}
.user-entry:hover {
  background: rgba(255, 255, 255, 0.6);
}
.user-avatar {
  background: var(--grad);
  color: #fff;
  font-size: var(--text-xs);
  font-weight: 600;
  box-shadow: 0 4px 12px rgba(29, 111, 224, 0.28);
}
.user-name {
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--ink);
}
.user-caret {
  font-size: var(--text-xs);
  color: var(--ink-mute);
}
.user-account {
  font-size: var(--text-xs);
  line-height: 20px;
  color: var(--ink-mute);
}

.admin-body {
  height: calc(100vh - 60px);
}

/* 侧栏：玻璃 + 浮起 8px，活跃项渐变药丸（管理端最醒目的结构信号） */
.admin-aside {
  width: 216px;
  padding: 20px 14px;
  margin: 12px 0 12px 12px;
  border-radius: var(--radius-card);
  background: var(--glass);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  border: 1px solid var(--glass-line);
  box-shadow: var(--shadow-surface), var(--shadow-raised), var(--glass-sheen);
  overflow: visible;
}

.admin-menu {
  border-right: none;
  background: transparent;
}
.admin-menu .el-menu-item {
  position: relative;
  height: 42px;
  line-height: 42px;
  padding: 0 14px;
  border-radius: var(--radius-control);
  margin-bottom: 6px;
  font-size: var(--text-sm);
  color: var(--ink-soft);
  font-weight: 500;
}
.admin-menu .el-menu-item:hover {
  background: rgba(255, 255, 255, 0.62);
  color: var(--ink);
}
.admin-menu .el-menu-item.is-active {
  background: var(--grad);
  color: #fff;
  font-weight: 600;
  box-shadow: var(--shadow-brand);
}

.admin-main {
  background: transparent;
  padding: 0;
  overflow-y: auto;
}
</style>