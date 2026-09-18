<template>
  <el-container class="student-layout">
    <!-- 顶部栏 -->
    <el-header class="student-header">
      <div class="header-left">
        <div class="logo-badge">
          <el-icon :size="16"><OfficeBuilding /></el-icon>
        </div>
        <span class="header-logo">高校实验室预约管理系统</span>
        <span class="role-chip">学生端</span>
      </div>
      <div class="header-right">
        <el-menu mode="horizontal" :default-active="activeMenu" router class="header-menu" :ellipsis="false">
          <el-menu-item index="/student/home">
            <el-icon><School /></el-icon>
            <span>教室列表</span>
          </el-menu-item>
          <el-menu-item index="/student/my-reservations">
            <el-icon><Tickets /></el-icon>
            <span>我的预约</span>
          </el-menu-item>
          <el-menu-item index="/student/calendar">
            <el-icon><Calendar /></el-icon>
            <span>日历总览</span>
          </el-menu-item>
          <el-menu-item index="/student/profile">
            <el-icon><User /></el-icon>
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
      <router-view />
    </el-main>

    <!-- 全局悬浮 AI 预约助手（R7，需求文档 2.4：学生端所有页面右下角；enabled=false 时悬浮球自动隐藏） -->
    <AiAssistant />
  </el-container>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  CaretBottom,
  Calendar,
  OfficeBuilding,
  School,
  SwitchButton,
  Tickets,
  User
} from '@element-plus/icons-vue'
import AiAssistant from '@/components/ai/AiAssistant.vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const userInfo = computed(() => userStore.userInfo)

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
  height: 60px;
  padding: 0 28px;
  background: #fff;
  border-bottom: 1px solid var(--border-color-light);
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo-badge {
  width: 34px;
  height: 34px;
  border-radius: var(--radius-md);
  background: var(--brand-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.header-logo {
  font-size: 17px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 0.5px;
}

.role-chip {
  font-size: 11px;
  padding: 2px 10px;
  border-radius: 12px;
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  font-weight: 600;
  letter-spacing: 0.5px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-menu {
  border-bottom: none;
  margin-right: 8px;
}

.header-menu .el-menu-item {
  height: 60px;
  line-height: 60px;
  font-size: 14px;
  color: var(--text-regular);
  font-weight: 500;
  border-bottom: none;
  margin: 0 4px;
  border-radius: var(--radius-md);
}
.header-menu .el-menu-item .el-icon {
  font-size: 16px;
}
.header-menu .el-menu-item:hover {
  background: var(--brand-primary-lighter);
  color: var(--brand-primary);
}
.header-menu .el-menu-item.is-active {
  color: var(--brand-primary);
  font-weight: 600;
}
.header-menu .el-menu-item.is-active::after {
  content: '';
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: 8px;
  width: 20px;
  height: 3px;
  border-radius: 2px;
  background: var(--brand-primary);
}

/* 用户入口 */
.user-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 10px;
  border-radius: 16px;
  transition: background-color 0.15s;
}
.user-entry:hover {
  background: var(--brand-primary-lighter);
}
.user-avatar {
  background: var(--brand-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}
.user-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-regular);
}
.user-caret {
  font-size: 12px;
  color: var(--text-placeholder);
}
.user-account {
  font-size: 12px;
  color: var(--text-placeholder);
}

.student-main {
  background: var(--bg-page);
  padding: 20px 24px;
  overflow-y: auto;
}

/* ---------- 窄屏适配（P3-8：<800px 顶栏压缩，菜单仅留图标；<560px 隐藏品牌文字） ---------- */
@media (max-width: 800px) {
  .student-header {
    padding: 0 12px;
  }
  .role-chip {
    display: none;
  }
  .header-menu {
    margin-right: 4px;
  }
  .header-menu .el-menu-item {
    margin: 0 2px;
    padding: 0 10px;
  }
  /* 隐藏菜单文字仅留图标，避免 4 个菜单窄屏溢出 */
  .header-menu .el-menu-item span {
    display: none;
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
    padding: 14px 12px;
  }
}
</style>
