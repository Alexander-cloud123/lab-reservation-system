import { createRouter, createWebHistory } from 'vue-router'
import { getToken, getStoredUser } from '@/utils/auth'

/**
 * 路由表 + 全局前置守卫（spec.md 3.2）
 *  - 未登录访问受保护页面 → 跳转登录页并携带回跳地址
 *  - 已登录访问登录/注册页 → 跳回角色首页
 *  - 角色不符访问对方端页面 → 跳回自己角色首页
 */
const routes = [
  { path: '/', redirect: '/student/home' },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/common/Login.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/common/Register.vue'),
    meta: { public: true, title: '注册' }
  },
  {
    path: '/student/home',
    name: 'StudentHome',
    component: () => import('@/views/student/StudentHome.vue'),
    meta: { requiresAuth: true, roles: [0], title: '学生首页' }
  },
  {
    path: '/admin/home',
    name: 'AdminHome',
    component: () => import('@/views/admin/AdminHome.vue'),
    meta: { requiresAuth: true, roles: [1], title: '管理后台首页' }
  },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/** 角色首页映射 */
const roleHome = { 0: '/student/home', 1: '/admin/home' }

router.beforeEach((to) => {
  const token = getToken()
  const user = getStoredUser()

  // 公开页面（登录/注册）
  if (to.meta.public) {
    if (token) {
      // 已登录访问登录/注册页 → 跳回角色首页
      return roleHome[user && user.role] || '/student/home'
    }
    return true
  }

  // 受保护页面：未登录 → 登录页（携带回跳地址）
  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 角色校验：访问非本角色页面 → 回自己首页
  if (to.meta.roles && !to.meta.roles.includes(user && user.role)) {
    return roleHome[user && user.role] || '/student/home'
  }

  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} - 高校实验室预约管理系统` : '高校实验室预约管理系统'
})

export default router
