<template>
  <el-container class="admin-layout">
    <!-- 顶部栏 -->
    <el-header class="admin-header">
      <div class="header-left">
        <div class="logo-badge" aria-hidden="true">A101</div>
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
        <router-view />
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

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  padding: 0 32px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
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
  height: 26px;
  padding: 0 9px;
  border-radius: var(--radius-sm);
  background: var(--brand-soft);
  color: var(--brand);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--text-sm);
  font-weight: 600;
  letter-spacing: 0.06em;
  font-variant-numeric: tabular-nums;
}

.header-logo {
  font-size: var(--text-base);
  font-weight: 600;
  line-height: 24px;
  color: var(--ink);
  letter-spacing: -0.01em;
}

.role-chip {
  padding-left: 10px;
  border-left: 1px solid var(--line);
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
  padding: 4px 8px;
  border-radius: var(--radius-md);
  transition: background-color 0.15s;
}
.user-entry:hover {
  background: var(--brand-soft);
}
.user-avatar {
  background: var(--brand);
  color: var(--surface);
  font-size: var(--text-xs);
  font-weight: 600;
}
.user-name {
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--ink);
}
.user-caret {
  font-size: var(--text-xs);
  color: var(--text-placeholder);
}
.user-account {
  font-size: var(--text-xs);
  line-height: 20px;
  color: var(--text-placeholder);
}

.admin-body {
  height: calc(100vh - 60px);
}

/* 侧栏：白面 + 选中项墨蓝浅底（不做左侧竖线） */
.admin-aside {
  background: var(--surface);
  border-right: 1px solid var(--line);
  padding: 16px 12px;
}

.admin-menu {
  border-right: none;
  background: transparent;
}
.admin-menu .el-menu-item {
  position: relative;
  height: 40px;
  line-height: 40px;
  padding: 0 12px;
  border-radius: var(--radius-control);
  margin-bottom: 4px;
  font-size: var(--text-sm);
  color: var(--ink-soft);
  font-weight: 500;
}
.admin-menu .el-menu-item .el-icon {
  font-size: var(--text-md);
  color: var(--text-regular);
  margin-right: 8px;
}
.admin-menu .el-menu-item:hover {
  background: var(--bg-fill);
  color: var(--ink);
}
.admin-menu .el-menu-item:hover .el-icon {
  color: var(--ink);
}
.admin-menu .el-menu-item.is-active {
  background: var(--brand-soft);
  color: var(--brand);
  font-weight: 600;
}
.admin-menu .el-menu-item.is-active::before {
  content: '';
  position: absolute;
  display: none;
}
.admin-menu .el-menu-item.is-active .el-icon {
  color: var(--brand-primary);
}

.admin-main {
  background: var(--page);
  padding: 0;
  overflow-y: auto;
}
</style>
