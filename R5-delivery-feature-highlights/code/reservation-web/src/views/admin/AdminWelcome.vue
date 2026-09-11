<template>
  <div class="welcome-wrap">
    <el-card shadow="never" class="welcome-card">
      <h3>你好，{{ userInfo ? userInfo.name : '' }}</h3>
      <p class="welcome-tip">
        欢迎使用高校实验室预约管理系统管理端。请通过左侧菜单进入「用户管理」或「教室资源管理」模块；预约审核、预约记录、数据看板等模块将在后续迭代上线。
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
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const userInfo = computed(() => userStore.userInfo)
</script>

<style scoped>
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
