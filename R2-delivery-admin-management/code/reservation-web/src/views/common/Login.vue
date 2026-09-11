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
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

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

/** 登录：成功后按角色跳转（优先回跳地址） */
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
  } catch (e) {
    // 错误提示已由 request 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>
