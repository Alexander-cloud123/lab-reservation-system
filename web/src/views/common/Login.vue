<template>
  <div class="login-page">
    <!-- 左侧品牌面板：渐变光主场 + 课表网格 + 门牌 + 一句定位 -->
    <div class="login-brand">
      <div class="brand-grid" aria-hidden="true" />
      <div class="brand-inner">
        <div class="brand-code">
          <span class="brand-code-num">预约</span>
          <span class="brand-code-note">08:00 至 22:00 开放</span>
        </div>
        <h1 class="brand-name">
          高校实验室<br />预约管理系统
        </h1>
        <p class="brand-slogan">把空闲的教室，变成可查、可约、可追溯的时段。</p>

        <!-- 静态刻度尺：与首页「时刻带」同源，说明 08:00–22:00 的可约窗口 -->
        <div class="brand-ruler" aria-hidden="true">
          <span class="brand-window" />
          <span
            v-for="hour in rulerHours"
            :key="hour"
            class="brand-tick"
            :class="{ 'is-major': hour % 2 === 0 }"
            :style="{ left: (hour / 24) * 100 + '%' }"
          />
        </div>

        <ul class="brand-steps">
          <li>
            <span class="step-title">找教室</span>
            <span class="step-text">按楼栋、类型、日期与时段筛选，剩余容量实时可见</span>
          </li>
          <li>
            <span class="step-title">提预约</span>
            <span class="step-text">填写用途后提交，前后端双重冲突校验，避免撞单</span>
          </li>
          <li>
            <span class="step-title">等审核</span>
            <span class="step-text">在「我的预约」跟踪状态，通过后按时到场使用</span>
          </li>
        </ul>
      </div>
      <div class="brand-footer">2026 课程设计，高校实验室预约管理系统</div>
    </div>

    <!-- 右侧登录表单区 -->
    <div class="login-panel">
      <div class="login-card">
        <h2 class="login-title">欢迎回来</h2>
        <p class="login-subtitle">请选择身份并登录您的账号</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-width="0" size="large" @keyup.enter="handleLogin" @validate="onFormValidate">
          <el-form-item prop="role">
            <el-radio-group v-model="form.role" class="role-group">
              <el-radio-button :value="0">学 生</el-radio-button>
              <el-radio-button :value="1">管理员</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item prop="username">
            <!-- 自定义错误渲染：带 id + role=alert，输入框用 aria-invalid/aria-describedby 与之关联，
                 读屏器在字段上可读到错误（无障碍验收 D3 机器代理发现原实现无关联） -->
            <template #error="{ error }">
              <div id="login-username-error" role="alert" class="el-form-item__error">{{ error }}</div>
            </template>
            <el-input
              v-model="form.username"
              placeholder="请输入登录账号"
              aria-label="登录账号"
              :aria-invalid="fieldInvalid.username ? 'true' : undefined"
              :aria-describedby="fieldInvalid.username ? 'login-username-error' : undefined"
              :prefix-icon="User"
              clearable
            />
          </el-form-item>
          <el-form-item prop="password">
            <template #error="{ error }">
              <div id="login-password-error" role="alert" class="el-form-item__error">{{ error }}</div>
            </template>
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              aria-label="密码"
              :aria-invalid="fieldInvalid.password ? 'true' : undefined"
              :aria-describedby="fieldInvalid.password ? 'login-password-error' : undefined"
              :prefix-icon="Lock"
              show-password
              clearable
            />
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
import { Lock, User } from '@element-plus/icons-vue'
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

/** 字段校验失败态：驱动输入框 aria-invalid / aria-describedby（与错误提示元素建立关联） */
const fieldInvalid = reactive({ username: false, password: false })

/** el-form 的 validate 事件：任一字段校验后触发（prop, isValid, message） */
function onFormValidate(prop, isValid) {
  if (prop === 'username' || prop === 'password') {
    fieldInvalid[prop] = !isValid
  }
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
/** 刻度尺：全天 0–24 小时，每小时一枚短刻度 */
const rulerHours = Array.from({ length: 25 }, (_, index) => index)

</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1.06fr 1fr;
}

/* ---- 左侧：渐变主场（夜蓝→天光蓝→薄荷青）+ 网格光 + 右上角光环 ---- */
.login-brand {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 64px 8%;
  background: var(--grad);
  color: #fff;
  box-shadow: 24px 0 60px rgba(11, 42, 91, 0.2);
}
.brand-grid {
  position: absolute;
  inset: 0;
  background-image: repeating-linear-gradient(to right, rgba(255, 255, 255, 0.07) 0 1px, transparent 1px 88px),
    repeating-linear-gradient(to bottom, rgba(255, 255, 255, 0.07) 0 1px, transparent 1px 88px);
  pointer-events: none;
}
.login-brand::after {
  content: '';
  position: absolute;
  top: -260px;
  right: -200px;
  width: 640px;
  height: 640px;
  border-radius: 50%;
  pointer-events: none;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.26), transparent 62%);
}
.brand-inner {
  position: relative;
  z-index: 1;
  max-width: 480px;
}
.brand-code {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 36px;
}
/* 门牌数字：全站仅两处大字号之一（另一处是首页 hero 主时钟） */
.brand-code-num {
  font-family: var(--font-family-sans);
  font-size: var(--text-display-xl);
  font-weight: 600;
  line-height: 1;
  letter-spacing: 0.02em;
  color: #fff;
  text-shadow: 0 2px 24px rgba(4, 20, 46, 0.28);
}
.brand-code-note {
  font-size: var(--text-sm);
  color: rgba(255, 255, 255, 0.72);
  letter-spacing: 0.04em;
}
.brand-name {
  font-size: var(--text-xl);
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: -0.01em;
  color: #fff;
  margin-bottom: 18px;
}
.brand-slogan {
  font-size: var(--text-base);
  line-height: 1.75;
  color: rgba(255, 255, 255, 0.78);
  margin-bottom: 22px;
}

/* 静态刻度尺：0–24 小时全天域，08:00–22:00 为可约窗口（与首页时刻带同源） */
.brand-ruler {
  position: relative;
  height: 30px;
  margin-bottom: 36px;
  border-top: 1px solid rgba(255, 255, 255, 0.2);
}
.brand-window {
  position: absolute;
  top: -1px;
  left: 33.3333%;
  width: 58.3334%;
  height: 2px;
  background: var(--sun);
  box-shadow: 0 0 12px rgba(255, 154, 60, 0.5);
}
.brand-tick {
  position: absolute;
  top: 0;
  width: 1px;
  height: 6px;
  background: rgba(255, 255, 255, 0.26);
}
.brand-tick.is-major {
  height: 10px;
  background: rgba(255, 255, 255, 0.44);
}
.brand-steps {
  list-style: none;
  padding: 0;
  display: grid;
  gap: 22px;
}
.brand-steps li {
  display: grid;
  gap: 4px;
  padding-left: 16px;
  border-left: 2px solid rgba(255, 255, 255, 0.22);
}
.step-title {
  font-size: var(--text-base);
  font-weight: 600;
  color: #fff;
}
.step-text {
  font-size: var(--text-sm);
  line-height: 1.65;
  color: rgba(255, 255, 255, 0.7);
}
.brand-footer {
  position: absolute;
  bottom: 40px;
  left: 8%;
  font-size: var(--text-xs);
  color: rgba(255, 255, 255, 0.46);
  z-index: 1;
}

/* ---- 右侧：浅蓝底 + 光晕 + 一块玻璃登录面板 ---- */
.login-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 56px 48px;
  background-color: var(--page);
  background-image: radial-gradient(50% 40% at 80% 10%, rgba(34, 195, 179, 0.16), transparent 70%),
    radial-gradient(50% 40% at 20% 92%, rgba(29, 111, 224, 0.14), transparent 72%);
}
.login-card {
  width: 420px;
  max-width: 100%;
  padding: 40px 36px 34px;
  border-radius: var(--radius-lg);
  background: var(--glass-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  border: 1px solid var(--glass-line);
  box-shadow: var(--shadow-overlay), var(--glass-sheen);
  animation: reveal-up var(--dur-3) var(--ease-out) both;
}
.login-title {
  font-size: var(--text-xl);
  font-weight: 600;
  line-height: 1.3;
  letter-spacing: -0.02em;
  color: var(--ink);
  margin-bottom: 8px;
}
.login-subtitle {
  font-size: var(--text-sm);
  line-height: 1.6;
  color: var(--ink-soft);
  margin-bottom: 28px;
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
  height: 42px;
  border-radius: var(--radius-control) !important;
  font-weight: 500;
  font-size: var(--text-sm);
}
.role-group :deep(.el-radio-button__original-radio + .el-radio-button__inner:hover) {
  color: var(--brand);
}
.role-group :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background-image: var(--grad);
  border-color: transparent;
  box-shadow: var(--shadow-brand);
}
.login-btn {
  width: 100%;
  height: 46px;
  margin-top: 10px;
  font-size: var(--text-base);
  font-weight: 600;
  letter-spacing: 0.08em;
}
.login-footer {
  margin-top: 20px;
  font-size: var(--text-sm);
  line-height: 1.6;
  color: var(--ink-soft);
}
.login-footer a {
  color: var(--brand);
  text-decoration: none;
  font-weight: 500;
}
.login-footer a:hover {
  text-decoration: underline;
}

@media (max-width: 960px) {
  .login-page {
    grid-template-columns: 1fr;
  }
  .login-brand {
    display: none;
  }
}
</style>