<template>
  <div class="login-page">
    <!-- 左侧品牌区（扁平深蓝面板，无渐变/光晕） -->
    <div class="login-brand">
      <div class="brand-inner">
        <div class="brand-logo">
          <el-icon :size="30"><OfficeBuilding /></el-icon>
        </div>
        <h1 class="brand-name">高校实验室预约管理系统</h1>
        <p class="brand-slogan">让每一间教室，都在最需要的时候被使用</p>
        <ul class="brand-points">
          <li><el-icon><CircleCheckFilled /></el-icon>双重冲突校验，预约零冲突</li>
          <li><el-icon><Calendar /></el-icon>日历总览 + AI 智能推荐</li>
          <li><el-icon><DataLine /></el-icon>可视化数据看板，资源一目了然</li>
        </ul>
      </div>
      <div class="brand-footer">@ 2026 高校实验室预约管理系统 · 课程设计项目</div>
    </div>

    <!-- 右侧登录表单区 -->
    <div class="login-panel">
      <div class="login-card">
        <h2 class="login-title">欢迎回来</h2>
        <p class="login-subtitle">请选择身份并登录您的账号</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-width="0" size="large" @keyup.enter="handleLogin">
          <el-form-item prop="role">
            <el-radio-group v-model="form.role" class="role-group">
              <el-radio-button :value="0">学 生</el-radio-button>
              <el-radio-button :value="1">管理员</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="请输入登录账号" aria-label="登录账号" :prefix-icon="User" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" aria-label="密码" :prefix-icon="Lock" show-password clearable />
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
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElNotification } from 'element-plus'
import { Lock, User, OfficeBuilding, CircleCheckFilled, Calendar, DataLine } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { useUserStore } from '@/stores/user'
import { getMyReservations } from '@/api/reservation'

// 为满足 multi-word 规则并便于 devtools 辨识
defineOptions({ name: 'LoginView' })

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
 * 顶部给出非阻塞的温和提醒；仅学生端生效，提醒失败不阻断登录
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
      // 需求 2.4「登录后若有当日即将开始的预约，顶部温和提醒」：用顶部非阻塞通知，
      // 不用需点「知道了」才能继续的模态框（会打断登录跳转）
      ElNotification({
        title: '预约即将开始提醒',
        message: `您有一个「${upcoming.classroomName}」预约将于 ${upcoming.reserveDate} ${upcoming.startTime} 开始，请准时到场。`,
        type: 'warning',
        duration: 8000,
        showClose: true
      })
    }
  } catch {
    // 提醒查询失败不影响登录流程（温和降级）
  }
}

/** 登录：成功后按角色跳转（优先回跳地址）；学生登录后检查预约提醒 */
async function handleLogin() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    const user = await userStore.login({ ...form })
    ElMessage.success('登录成功')
    const redirect = route.query.redirect
    // L12 修复：回跳地址仅接受站内路径（以单个 / 开头且非协议相对路径），
    // 防止外部构造 ?redirect=https://evil.com 造成开放重定向
    const isSafeRedirect =
      typeof redirect === 'string' &&
      redirect.startsWith('/') &&
      !redirect.startsWith('//') &&
      !redirect.startsWith('/http')
    const target = isSafeRedirect ? redirect : user.role === 1 ? '/admin/home' : '/student/home'
    router.push(target)
    if (user.role === 0) {
      checkUpcomingReminder()
    }
  } catch {
    // 错误提示已由 request 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  background: var(--bg-page);
}

/* ---- 左侧品牌区：扁平深蓝面板 + 规则圆环（排课表母题） ---- */
.login-brand {
  flex: 1.15;
  min-width: 0;
  background: var(--brand-primary);
  color: #fff;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 48px 7%;
  position: relative;
  overflow: hidden;
}
.login-brand::after {
  content: '';
  position: absolute;
  right: -120px;
  bottom: -120px;
  width: 340px;
  height: 340px;
  border-radius: 50%;
  border: 1.5px solid rgba(255, 255, 255, 0.2);
}
.login-brand::before {
  content: '';
  position: absolute;
  right: -40px;
  bottom: -40px;
  width: 220px;
  height: 220px;
  border-radius: 50%;
  border: 1.5px solid rgba(255, 255, 255, 0.16);
}

.brand-logo {
  width: 58px;
  height: 58px;
  border-radius: var(--radius-lg);
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.28);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 22px;
}
.brand-name {
  font-size: 30px;
  font-weight: 700;
  letter-spacing: 1px;
  margin-bottom: 12px;
}
.brand-slogan {
  font-size: 15px;
  color: rgba(255, 255, 255, 0.82);
  margin-bottom: 34px;
  letter-spacing: 0.5px;
}
.brand-points {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.brand-points li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.92);
}
.brand-points li .el-icon {
  font-size: 18px;
  color: rgba(255, 255, 255, 0.7);
}
.brand-footer {
  position: absolute;
  bottom: 28px;
  left: 7%;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
}

/* ---- 右侧表单区 ---- */
.login-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 24px;
}
.login-card {
  width: 400px;
  max-width: 100%;
  background: #fff;
  border-radius: var(--radius-lg);
  padding: 40px 36px 30px;
  box-shadow: 0 6px 24px rgba(28, 39, 51, 0.08);
  border: 1px solid var(--border-color-light);
}
.login-title {
  text-align: center;
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  margin-bottom: 6px;
}
.login-subtitle {
  text-align: center;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 26px;
}
.role-group {
  display: flex;
  width: 100%;
}
.role-group .el-radio-button {
  flex: 1;
  text-align: center;
}
/* 按钮内文字 span 属 Element Plus 组件内部元素，须 :deep 穿透 scoped 才能命中 */
.role-group :deep(.el-radio-button__inner) {
  width: 100%;
  border-radius: var(--radius-md) !important;
  font-weight: 500;
}
.role-group .el-radio-button:first-child :deep(.el-radio-button__inner) {
  border-radius: var(--radius-md) 0 0 var(--radius-md) !important;
}
.role-group .el-radio-button:last-child :deep(.el-radio-button__inner) {
  border-radius: 0 var(--radius-md) var(--radius-md) 0 !important;
}
.login-btn {
  width: 100%;
  height: 44px;
  font-size: 15px;
  letter-spacing: 2px;
}
.login-footer {
  margin-top: 20px;
  text-align: center;
  font-size: 13px;
  color: var(--text-secondary);
}
.login-footer a {
  color: var(--brand-primary);
  text-decoration: none;
  font-weight: 500;
}
.login-footer a:hover {
  text-decoration: underline;
}

@media (max-width: 900px) {
  .login-brand {
    display: none;
  }
}
</style>
