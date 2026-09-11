<template>
  <div class="home-page">
    <header class="home-header">
      <div class="home-header-left">
        <span class="home-logo">高校实验室预约管理系统</span>
        <el-tag type="warning" size="small">管理端</el-tag>
      </div>
      <el-button type="danger" plain size="small" @click="handleLogout">退出登录</el-button>
    </header>

    <main class="home-body">
      <el-card shadow="never" class="welcome-card">
        <h3>你好，{{ userInfo ? userInfo.name : '' }}</h3>
        <p class="welcome-tip">
          欢迎使用高校实验室预约管理系统管理端。教室管理、预约审核、用户管理等模块将在后续迭代上线，当前账号已通过管理员身份校验。
        </p>
        <el-descriptions :column="2" border class="info-table">
          <el-descriptions-item label="登录账号">{{ userInfo ? userInfo.username : '-' }}</el-descriptions-item>
          <el-descriptions-item label="姓名">{{ userInfo ? userInfo.name : '-' }}</el-descriptions-item>
          <el-descriptions-item label="角色">
            <el-tag type="warning" size="small">管理员</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="账号状态">
            <el-tag :type="userInfo && userInfo.status === 1 ? 'success' : 'danger'" size="small">
              {{ userInfo && userInfo.status === 1 ? '正常' : '禁用' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const userInfo = computed(() => userStore.userInfo)

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
.home-page {
  min-height: 100%;
  background: #f5f7fa;
}

.home-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 24px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.home-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.home-logo {
  font-size: 16px;
  font-weight: 600;
  color: #1f3a93;
}

.home-body {
  max-width: 960px;
  margin: 24px auto;
  padding: 0 16px;
}

.welcome-card h3 {
  margin-bottom: 8px;
}

.welcome-tip {
  color: #909399;
  font-size: 13px;
  margin-bottom: 20px;
}

.info-table {
  margin-top: 8px;
}
</style>
