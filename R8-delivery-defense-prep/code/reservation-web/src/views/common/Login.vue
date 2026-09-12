<template>
  <div class="login-page">
    <div class="login-card">
      <h2 class="login-title">高校实验室预约管理系统</h2>
      <p class="login-subtitle">基于双重校验机制 · 学生/管理员双端</p>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="0" size="large" @keyup.enter="handleLogin">
        <el-form-item prop="role">
          <el-radio-group v-model="form.role">
            <el-radio-button :value="0">学 生</el-radio-button>
            <el-radio-button :value="1">管理员</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="请输入登录账号" :prefix-icon="User" clearable />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" :prefix-icon="Lock" show-password clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">登 录</el-button>
        </el-form-item>
      </el-form>

      <div class="login-footer">
        还没有账号？<router-link to="/register">立即注册</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { useUserStore } from '@/stores/user'
import { getMyReservations } from '@/api/reservation'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({
  username: '',
  password: '',
  role: 0
})

const rules = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

/**
 * 登录提醒（R4 体验细节）：登录后若存在「已通过且即将开始（24 小时内）」的预约，
 * 弹出温和提醒；仅学生端生效，提醒失败不阻断登录
 */
async function checkUpcomingReminder() {
  try {
    const res = await getMyReservations({ page: 1, size: 50, status: 1 })
    const now = dayjs()
    const upcoming = (res.data.records || []).find((r) => {
      const start = dayjs(`${r.reserveDate} ${r.startTime}`)
      return start.isAfter(now) && start.isBefore(now.add(24, 'hour'))
    })
    if (upcoming) {
      await ElMessageBox.alert(
        `您有一个「${upcoming.classroomName}」预约将于 ${upcoming.reserveDate} ${upcoming.startTime} 开始，请准时到场。`,
        '预约即将开始提醒',
        { confirmButtonText: '知道了', type: 'warning' }
      )
    }
  } catch (e) {
    // 提醒查询失败不影响登录流程（温和降级）
  }
}

/** 登录：成功后按角色跳转（优先回跳地址）；学生登录后检查预约提醒 */
async function handleLogin() {
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }
  loading.value = true
  try {
    const user = await userStore.login({ ...form })
    ElMessage.success('登录成功')
    const redirect = route.query.redirect
    const target = redirect && typeof redirect === 'string' ? redirect : user.role === 1 ? '/admin/home' : '/student/home'
    router.push(target)
    if (user.role === 0) {
      checkUpcomingReminder()
    }
  } catch (e) {
    // 错误提示已由 request 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>
