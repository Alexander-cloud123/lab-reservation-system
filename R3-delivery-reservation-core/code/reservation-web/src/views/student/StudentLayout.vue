<template>
  <el-container class="student-layout">
    <!-- 顶部栏 -->
    <el-header class="student-header">
      <div class="header-left">
        <span class="header-logo">高校实验室预约管理系统</span>
        <el-tag type="success" size="small">学生端</el-tag>
      </div>
      <div class="header-right">
        <el-menu mode="horizontal" :default-active="activeMenu" router class="header-menu" :ellipsis="false">
          <el-menu-item index="/student/home">教室列表</el-menu-item>
          <el-menu-item index="/student/my-reservations">我的预约</el-menu-item>
        </el-menu>
        <span class="header-user">{{ userInfo ? userInfo.name : '' }}（{{ userInfo ? userInfo.username : '-' }}）</span>
        <el-button type="danger" plain size="small" @click="handleLogout">退出登录</el-button>
      </div>
    </el-header>

    <!-- 主内容区 -->
    <el-main class="student-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const userInfo = computed(() => userStore.userInfo)

/** 顶部导航高亮：跟随当前路由（详情页归属教室列表） */
const activeMenu = computed(() => (route.path.startsWith('/student/classrooms') ? '/student/home' : route.path))

onMounted(() => {
  // 拉取最新用户信息，验证 Token 有效性（失效时 request 拦截器自动跳登录页）
  userStore.fetchInfo().catch(() => {})
})

/** 退出登录（二次确认） */
async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
    userStore.logout()
    router.push('/login')
  } catch (e) {
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
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-logo {
  font-size: 16px;
  font-weight: 600;
  color: #1f3a93;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.header-menu {
  border-bottom: none;
}

.header-menu .el-menu-item {
  height: 56px;
  line-height: 56px;
}

.header-user {
  font-size: 13px;
  color: #606266;
}

.student-main {
  background: #f5f7fa;
  padding: 16px;
  overflow-y: auto;
}
</style>
