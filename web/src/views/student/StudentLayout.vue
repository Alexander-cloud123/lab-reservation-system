<template>
  <el-container class="student-layout">
    <!-- 顶部栏 -->
    <el-header class="student-header">
      <div class="header-left">
        <div class="logo-badge" aria-hidden="true">预约</div>
        <span class="header-logo">高校实验室预约管理系统</span>
        <span class="role-chip">学生端</span>
      </div>
      <div class="header-right">
        <el-menu mode="horizontal" :default-active="activeMenu" router class="header-menu" :ellipsis="false">
          <el-menu-item index="/student/home">
            <span>教室列表</span>
          </el-menu-item>
          <el-menu-item index="/student/my-reservations">
            <span>我的预约</span>
          </el-menu-item>
          <el-menu-item index="/student/calendar">
            <span>日历总览</span>
          </el-menu-item>
          <el-menu-item v-if="aiReady" index="/student/ai">
            <span>AI 助手</span>
          </el-menu-item>
          <el-menu-item index="/student/profile">
            <span>个人中心</span>
          </el-menu-item>
        </el-menu>

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
              <el-dropdown-item divided command="profile">
                <el-icon><User /></el-icon>个人中心
              </el-dropdown-item>
              <el-dropdown-item command="logout" divided>
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <!-- 主内容区 -->
    <el-main class="student-main">
      <router-view v-slot="{ Component, route: currentRoute }">
        <transition name="page-swap" mode="out-in">
          <component :is="Component" :key="currentRoute.path" />
        </transition>
      </router-view>
    </el-main>

    <!-- 全局悬浮 AI 预约助手（R7，需求文档 2.4：学生端所有页面右下角；enabled=false 时悬浮球自动隐藏） -->
    <AiAssistant />
  </el-container>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { CaretBottom, SwitchButton, User } from '@element-plus/icons-vue'
import AiAssistant from '@/components/ai/AiAssistant.vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const userInfo = computed(() => userStore.userInfo)
/** AI 双层开关都开启时才露出对话入口（与悬浮球同一份探测缓存） */
const aiReady = computed(() => userStore.aiEnabled === true)

/** 头像占位：取姓名末位，多字取后两字 */
const avatarText = computed(() => {
  const name = userInfo.value ? userInfo.value.name : ''
  return name ? name.slice(-2) : '客'
})

/** 顶部导航高亮：跟随当前路由（详情页归属教室列表） */
const activeMenu = computed(() => (route.path.startsWith('/student/classrooms') ? '/student/home' : route.path))

onMounted(() => {
  // 拉取最新用户信息，验证 Token 有效性（失效时 request 拦截器自动跳登录页）
  userStore.fetchInfo().catch(() => {})
})

/** 用户下拉菜单指令 */
function handleUserCommand(cmd) {
  if (cmd === 'profile') {
    router.push('/student/profile')
  } else if (cmd === 'logout') {
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
.student-layout {
  min-height: 100vh;
}

.student-header {
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
  gap: 8px;
}

.header-menu {
  border-bottom: none;
  margin-right: 8px;
  background: transparent;
}

.header-menu .el-menu-item {
  height: 60px;
  line-height: 60px;
  padding: 0 14px;
  margin: 0;
  font-size: var(--text-sm);
  color: var(--ink-soft);
  font-weight: 500;
  border-bottom: none;
  border-radius: 0;
}
.header-menu .el-menu-item .el-icon {
  font-size: var(--text-md);
}
.header-menu .el-menu-item:hover {
  background: transparent;
  color: var(--brand-primary);
}
.header-menu .el-menu-item.is-active {
  color: var(--brand);
  font-weight: 600;
}
.header-menu .el-menu-item.is-active::after {
  content: '';
  left: 14px;
  right: 14px;
  right: 12px;
  bottom: 0;
  height: 2px;
  background: var(--brand);
}

/* 用户入口 */
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
  color: var(--text-placeholder);
}
.user-account {
  font-size: var(--text-xs);
  line-height: 20px;
  color: var(--text-placeholder);
}

.student-main {
  /* 透明：让 #app 的页面底纹（排课表行线）透出，内容面自己有不透明底 */
  background: transparent;
  padding: 0;
  overflow-y: auto;
}

/* ---------- 窄屏适配：菜单保留简短文字，菜单区自身横向滚动 ---------- */
@media (max-width: 800px) {
  .student-header {
    gap: var(--space-sm);
    max-width: 100vw;
    padding: 0 12px;
    overflow: hidden;
  }
  .header-left {
    flex: 0 0 auto;
  }
  .header-right {
    flex: 1 1 auto;
    min-width: 0;
  }
  .header-right :deep(.el-dropdown) {
    flex: 0 0 auto;
  }
  .role-chip {
    display: none;
  }
  .header-menu {
    flex: 1 1 auto;
    flex-wrap: nowrap;
    min-width: 0;
    margin-right: 4px;
    overflow-x: auto;
    overflow-y: hidden;
    overscroll-behavior-x: contain;
    scrollbar-width: thin;
  }
  .header-menu::-webkit-scrollbar {
    height: 4px;
  }
  .header-menu .el-menu-item {
    flex: 0 0 auto;
    margin: 0;
    padding: 0 10px;
  }
  .header-menu .el-menu-item span {
    display: inline;
  }
  .user-entry {
    padding-right: 4px;
    padding-left: 4px;
  }
  .user-name {
    display: none;
  }
}

@media (max-width: 560px) {
  .header-logo {
    display: none;
  }
  .student-main {
    padding: 12px;
  }
}
</style>
