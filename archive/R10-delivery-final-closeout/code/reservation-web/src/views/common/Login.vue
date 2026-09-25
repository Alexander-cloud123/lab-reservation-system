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

/* ---- 左侧品牌区：飞书品牌蓝实底 + 排课表细线网格（产品母题，非装饰渐变） ---- */
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
.login-brand::before {
  content: '';
  position: absolute;
  inset: 0;
  /* 时段网格：教室 × 时段的产品本体意象，1px 细线示意结构 */
  background-image: repeating-linear-gradient(
      to right,
      rgba(255, 255, 255, 0.1) 0 1px,
      transparent 1px 64px
    ),
    repeating-linear-gradient(to bottom, rgba(255, 255, 255, 0.1) 0 1px, transparent 1px 64px);
  pointer-events: none;
}
.brand-inner {
  position: relative;
  z-index: 1;
}

.brand-logo {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.24);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
}
.brand-name {
  font-size: 28px;
  font-weight: 600;
  line-height: 36px;
  margin-bottom: 8px;
}
.brand-slogan {
  font-size: 14px;
  line-height: 22px;
  color: rgba(255, 255, 255, 0.8);
  margin-bottom: 32px;
}
.brand-points {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.brand-points li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  line-height: 22px;
  color: rgba(255, 255, 255, 0.88);
}
.brand-points li .el-icon {
  font-size: 16px;
  color: rgba(255, 255, 255, 0.7);
}
.brand-footer {
  position: absolute;
  bottom: 28px;
  left: 7%;
  font-size: 12px;
  line-height: 20px;
  color: rgba(255, 255, 255, 0.5);
  z-index: 1;
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
  background: var(--bg-card);
  border-radius: var(--radius-lg);
  border: 1px solid var(--border-color-light);
  padding: 32px;
  box-shadow: var(--shadow-raised);
}
.login-title {
  font-size: 24px;
  font-weight: 600;
  line-height: 32px;
  color: var(--text-primary);
  margin-bottom: 6px;
}
.login-subtitle {
  font-size: 14px;
  line-height: 22px;
  color: var(--text-regular);
  margin-bottom: 24px;
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
  font-weight: 400;
}
.role-group .el-radio-button:first-child :deep(.el-radio-button__inner) {
  border-radius: var(--radius-md) 0 0 var(--radius-md) !important;
}
.role-group .el-radio-button:last-child :deep(.el-radio-button__inner) {
  border-radius: 0 var(--radius-md) var(--radius-md) 0 !important;
}
.login-btn {
  width: 100%;
  height: 40px;
  font-size: 14px;
}
.login-footer {
  margin-top: 16px;
  font-size: 14px;
  line-height: 22px;
  color: var(--text-regular);
}
.login-footer a {
  color: var(--brand-primary);
  text-decoration: none;
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
