<template>
  <el-container class="admin-layout">
    <!-- 顶部栏 -->
    <el-header class="admin-header">
      <div class="header-left">
        <span class="header-logo">高校实验室预约管理系统</span>
        <el-tag type="warning" size="small">管理端</el-tag>
      </div>
      <div class="header-right">
        <span class="header-user">{{ userInfo ? userInfo.name : '' }}（{{ userInfo ? userInfo.username : '-' }}）</span>
        <el-button type="danger" plain size="small" @click="handleLogout">退出登录</el-button>
      </div>
    </el-header>

    <el-container class="admin-body">
      <!-- 侧边导航菜单（管理端入口） -->
      <el-aside width="200px" class="admin-aside">
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
import { HomeFilled, User, School, Checked } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const userInfo = computed(() => userStore.userInfo)

/** 侧边菜单高亮：跟随当前路由 */
const activeMenu = computed(() => route.path)

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
.admin-layout {
  min-height: 100vh;
}

.admin-header {
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

.header-user {
  font-size: 13px;
  color: #606266;
}

.admin-body {
  height: calc(100vh - 56px);
}

.admin-aside {
  background: #fff;
  border-right: 1px solid #e4e7ed;
}

.admin-menu {
  border-right: none;
}

.admin-main {
  background: #f5f7fa;
  padding: 16px;
  overflow-y: auto;
}
</style>
