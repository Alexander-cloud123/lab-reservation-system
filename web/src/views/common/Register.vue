<template>
  <div class="register-page">
    <!-- 左侧渐变主场：与登录页同一母题 -->
    <div class="register-brand">
      <div class="brand-grid" aria-hidden="true" />
      <div class="brand-inner">
        <div class="brand-code">
          <span class="brand-code-num">NEW</span>
          <span class="brand-code-note">学生注册</span>
        </div>
        <h1 class="brand-name">高校实验室<br />预约管理系统</h1>
        <p class="brand-slogan">注册学生账号，凭学号实名预约，从找教室到按时到场，一条链走完。</p>
        <ul class="brand-steps">
          <li>
            <span class="step-title">填资料</span>
            <span class="step-text">学号、姓名、账号与密码，一次填完</span>
          </li>
          <li>
            <span class="step-title">即登录</span>
            <span class="step-text">注册成功后直接回到登录页</span>
          </li>
          <li>
            <span class="step-title">开始约</span>
            <span class="step-text">双重冲突校验，只看真正可用的时段</span>
          </li>
        </ul>
      </div>
      <div class="brand-footer">2026 课程设计，高校实验室预约管理系统</div>
    </div>

    <!-- 右侧注册表单区 -->
    <div class="register-panel">
      <div class="register-card">
        <h2 class="login-title">学生注册</h2>
        <p class="login-subtitle">注册成功后即可登录预约实验室 / 教室</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-width="80px" size="large">
          <el-form-item label="学号" prop="studentNo">
            <el-input v-model="form.studentNo" placeholder="请输入学号" maxlength="20" />
          </el-form-item>
          <el-form-item label="姓名" prop="name">
            <el-input v-model="form.name" placeholder="请输入真实姓名" maxlength="20" />
          </el-form-item>
          <el-form-item label="手机号" prop="phone">
            <el-input v-model="form.phone" placeholder="选填，11 位手机号" maxlength="11" />
          </el-form-item>
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="form.email" placeholder="选填" maxlength="50" />
          </el-form-item>
          <el-form-item label="登录账号" prop="username">
            <el-input v-model="form.username" placeholder="登录唯一账号" maxlength="32" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" placeholder="不少于 6 位" show-password />
          </el-form-item>
          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" placeholder="再次输入密码" show-password />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" class="register-btn" :loading="loading" @click="handleRegister">注 册</el-button>
          </el-form-item>
        </el-form>

        <div class="login-footer">
          已有账号？<router-link to="/login">返回登录</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { register as registerApi } from '@/api/user'

// 为满足 multi-word 规则并便于 devtools 辨识
defineOptions({ name: 'RegisterView' })

const router = useRouter()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  studentNo: '',
  name: '',
  phone: '',
  email: '',
  username: '',
  password: '',
  confirmPassword: ''
})

/** 确认密码一致性校验 */
function validateConfirmPassword(rule, value, callback) {
  if (!value) {
    callback(new Error('请再次输入密码'))
  } else if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

/** 手机号格式校验 */
function validatePhone(rule, value, callback) {
  if (!value) {
    callback()
  } else if (!/^1\d{10}$/.test(value)) {
    callback(new Error('手机号格式不正确'))
  } else {
    callback()
  }
}

const rules = {
  studentNo: [{ required: true, message: '请输入学号', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [{ validator: validatePhone, trigger: 'blur' }],
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码长度不能少于 6 位', trigger: 'blur' }
  ],
  confirmPassword: [{ validator: validateConfirmPassword, trigger: 'blur' }]
}

/** 注册：通过后跳转登录页 */
async function handleRegister() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await registerApi({ ...form })
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch {
    // 错误提示已由 request 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 1fr 1.06fr;
}

/* 左侧：渐变主场（信息密度低于登录页，但同一母题） */
.register-brand {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 64px 7%;
  background: var(--grad);
  color: #fff;
}
.brand-grid {
  position: absolute;
  inset: 0;
  background-image: repeating-linear-gradient(to right, rgba(255, 255, 255, 0.07) 0 1px, transparent 1px 88px),
    repeating-linear-gradient(to bottom, rgba(255, 255, 255, 0.07) 0 1px, transparent 1px 88px);
  pointer-events: none;
}
.register-brand::after {
  content: '';
  position: absolute;
  top: -220px;
  left: -180px;
  width: 560px;
  height: 560px;
  border-radius: 50%;
  pointer-events: none;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.24), transparent 62%);
}
.brand-inner {
  position: relative;
  z-index: 1;
  max-width: 440px;
}
.brand-code {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 32px;
}
.brand-code-num {
  font-family: var(--font-num);
  font-size: var(--text-display);
  font-weight: 600;
  line-height: 1;
  letter-spacing: 0.02em;
  color: #fff;
  font-variant-numeric: tabular-nums;
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
  margin-bottom: 28px;
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
  left: 7%;
  font-size: var(--text-xs);
  color: rgba(255, 255, 255, 0.46);
  z-index: 1;
}

/* 右侧：浅蓝底 + 光晕 + 玻璃表单面板 */
.register-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 40px;
  background-color: var(--page);
  background-image: radial-gradient(50% 40% at 18% 8%, rgba(29, 111, 224, 0.14), transparent 72%),
    radial-gradient(46% 38% at 88% 94%, rgba(34, 195, 179, 0.16), transparent 70%);
}
.register-card {
  width: 480px;
  max-width: 100%;
  padding: 36px 34px 30px;
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
  margin-bottom: 24px;
}
.register-btn {
  width: 100%;
  height: 46px;
  margin-top: 6px;
  font-size: var(--text-base);
  font-weight: 600;
  letter-spacing: 0.08em;
}
.login-footer {
  margin-top: 18px;
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
  .register-page {
    grid-template-columns: 1fr;
  }
  .register-brand {
    display: none;
  }
}
</style>