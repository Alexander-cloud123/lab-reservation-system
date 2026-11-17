<template>
  <div class="login-page">
    <!-- 左侧导视牌：白底课表网格 + 编号 + 三步流程（母题：教学楼门牌 / 排课表） -->
    <div class="login-brand">
      <div class="brand-grid" aria-hidden="true" />
      <div class="brand-inner">
        <div class="brand-code">
          <span class="code-block is-signal">A101</span>
          <span class="brand-code-note">教室预约 · 排课台</span>
        </div>
        <h1 class="brand-name">高校实验室预约管理系统</h1>
        <p class="brand-slogan">把空教室，变成可预约的时段。</p>
        <ol class="brand-steps">
          <li>
            <span class="step-no">01</span>
            <span class="step-text"><b>找教室</b>按关键词、楼栋、类型、日期筛出可用时段</span>
          </li>
          <li>
            <span class="step-no">02</span>
            <span class="step-text"><b>提预约</b>填写用途后提交，前后端双重冲突校验</span>
          </li>
          <li>
            <span class="step-no">03</span>
            <span class="step-text"><b>等审核</b>在「我的预约」跟踪状态，通过后按时到场</span>
          </li>
        </ol>
      </div>
      <div class="brand-footer">2026 课程设计 高校实验室预约管理系统</div>
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
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  background: var(--bg-page);
}

/* ---- 左侧导视牌：纸白底 + 课表网格 + 编号 + 三步流程 ---- */
.login-brand {
  flex: 1.15;
  min-width: 0;
  background: var(--bg-card);
  border-right: 1px solid var(--rule);
  color: var(--text-primary);
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 48px 7%;
  position: relative;
  overflow: hidden;
}
/* 课表网格：教室 × 时段的排课格，只做结构不做装饰 */
.brand-grid {
  position: absolute;
  inset: 0;
  background-image: repeating-linear-gradient(to right, var(--rule) 0 1px, transparent 1px 72px),
    repeating-linear-gradient(to bottom, var(--rule) 0 1px, transparent 1px 72px);
  opacity: 0.35;
  pointer-events: none;
}
.brand-inner {
  position: relative;
  z-index: 1;
  max-width: 520px;
}
.brand-code {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 28px;
}
.brand-code-note {
  font-size: 12px;
  line-height: 20px;
  color: var(--text-placeholder);
}
.brand-name {
  font-size: 30px;
  font-weight: 600;
  line-height: 40px;
  letter-spacing: -0.4px;
  margin-bottom: 10px;
}
.brand-slogan {
  font-size: 15px;
  line-height: 24px;
  color: var(--text-regular);
  margin-bottom: 32px;
}
.brand-steps {
  list-style: none;
  padding: 0;
  border-top: 1px solid var(--rule);
}
.brand-steps li {
  display: flex;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid var(--rule);
  font-size: 13px;
  line-height: 20px;
  color: var(--text-regular);
}
.step-no {
  flex: none;
  padding-top: 2px;
  font-family: var(--font-num);
  font-size: 12px;
  color: var(--text-placeholder);
  font-variant-numeric: tabular-nums;
}
.step-text b {
  margin-right: 8px;
  color: var(--text-primary);
  font-weight: 600;
}
.brand-footer {
  position: absolute;
  bottom: 28px;
  left: 7%;
  font-size: 12px;
  line-height: 20px;
  color: var(--text-placeholder);
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
  width: 380px;
  max-width: 100%;
  background: transparent;
  border: 0;
  padding: 0;
}
.login-title {
  font-size: 24px;
  font-weight: 600;
  line-height: 32px;
  letter-spacing: -0.2px;
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
