<template>
  <div class="register-page">
    <!-- 左侧品牌区（与登录页统一：扁平深蓝面板） -->
    <div class="register-brand">
      <div class="brand-inner">
        <div class="brand-logo">
          <el-icon :size="30"><OfficeBuilding /></el-icon>
        </div>
        <h1 class="brand-name">高校实验室预约管理系统</h1>
        <p class="brand-slogan">注册学生账号，开启高效预约体验</p>
        <ul class="brand-points">
          <li><el-icon><CircleCheckFilled /></el-icon>学号实名认证，预约安全可信</li>
          <li><el-icon><Calendar /></el-icon>日历总览 + AI 智能推荐</li>
          <li><el-icon><DataLine /></el-icon>可视化数据看板，资源一目了然</li>
        </ul>
      </div>
      <div class="brand-footer">@ 2026 高校实验室预约管理系统 · 课程设计项目</div>
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
import { OfficeBuilding, CircleCheckFilled, Calendar, DataLine } from '@element-plus/icons-vue'
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
  display: flex;
  background: var(--bg-page);
}

/* ---- 左侧品牌区（与登录页统一：飞书品牌蓝 + 排课表细线网格） ---- */
.register-brand {
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
.register-brand::before {
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
.register-panel {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 24px;
}
.register-card {
  width: 520px;
  max-width: 100%;
  background: var(--bg-card);
  border-radius: var(--radius-lg);
  padding: 32px;
  border: 1px solid var(--border-color-light);
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
  margin-bottom: 20px;
}
.register-btn {
  width: 100%;
  height: 40px;
  font-size: 14px;
}
.login-footer {
  margin-top: 8px;
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
  .register-brand {
    display: none;
  }
}
</style>
