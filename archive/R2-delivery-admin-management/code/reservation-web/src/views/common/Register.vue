<template>
  <div class="register-page">
    <div class="register-card">
      <h2 class="login-title">学生注册</h2>
      <p class="login-subtitle">注册成功后即可登录预约实验室/教室</p>

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
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
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
