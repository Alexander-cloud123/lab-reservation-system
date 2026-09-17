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
  } catch (e) {
    return
  }
  loading.value = true
  try {
    await registerApi({ ...form })
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch (e) {
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

/* ---- 左侧品牌区（与登录页统一：扁平深蓝面板 + 规则圆环） ---- */
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
.register-brand::after {
  content: '';
  position: absolute;
  right: -120px;
  bottom: -120px;
  width: 340px;
  height: 340px;
  border-radius: 50%;
  border: 1.5px solid rgba(255, 255, 255, 0.2);
}
.register-brand::before {
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
  background: #fff;
  border-radius: var(--radius-lg);
  padding: 32px 36px 24px;
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
  margin-bottom: 22px;
}
.register-btn {
  width: 100%;
  height: 44px;
  font-size: 15px;
  letter-spacing: 2px;
}
.login-footer {
  margin-top: 16px;
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
  .register-brand {
    display: none;
  }
}
</style>
