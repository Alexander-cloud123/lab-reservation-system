import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getToken, clearAuth } from '@/utils/auth'

/**
 * Axios 统一封装（spec.md 3.3）
 *  - baseURL: /api（开发环境经 Vite 代理转发到后端 8080）
 *  - 请求拦截：自动注入 Authorization: Bearer {token}
 *  - 响应拦截：code!==200 统一错误提示；401 清登录态并跳转登录页
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

// 请求拦截：Token 注入
request.interceptors.request.use(
  (config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截：统一错误处理与 401 跳转
request.interceptors.response.use(
  (response) => {
    // 文件流响应（responseType=blob，R6 预约记录导出）：不按 Result 结构解析，直接返回 Blob
    if (response.config.responseType === 'blob') {
      return response.data
    }
    const res = response.data
    if (res.code === 200) {
      return res
    }
    if (res.code === 401) {
      clearAuth()
      redirectToLogin()
      return Promise.reject(new Error(res.message || '登录已过期'))
    }
    if (res.code === 403) {
      ElMessage.error(res.message || '无权限访问该功能')
      return Promise.reject(new Error(res.message || '无权限访问'))
    }
    ElMessage.error(res.message || '操作失败，请稍后重试')
    return Promise.reject(new Error(res.message || '操作失败'))
  },
  (error) => {
    const status = error.response && error.response.status
    if (status === 401) {
      // 后端拦截器返回的 401：清登录态并跳转（blob 请求同样处理）
      clearAuth()
      redirectToLogin()
    } else if (status === 403) {
      // blob 响应体为文件流，需先解析 JSON 再提示
      if (error.config && error.config.responseType === 'blob' && error.response && error.response.data) {
        readBlobMessage(error.response.data).then((msg) => ElMessage.error(msg || '无权限访问该功能'))
      } else {
        ElMessage.error('无权限访问该功能')
      }
    } else {
      const message =
        (error.response && error.response.data && error.response.data.message) || '网络异常，请稍后重试'
      ElMessage.error(message)
    }
    return Promise.reject(error)
  }
)

/** 读取 Blob 中的错误信息（导出等 blob 请求失败时，后端以 JSON 形式返回 Result） */
function readBlobMessage(blob) {
  return blob.text().then((text) => {
    try {
      const data = JSON.parse(text)
      return data.message || ''
    } catch (e) {
      return ''
    }
  })
}

/** 401 统一跳转登录页（携带回跳地址） */
function redirectToLogin() {
  ElMessage.warning('登录已过期，请重新登录')
  const current = window.location.pathname + window.location.search
  if (!window.location.pathname.startsWith('/login')) {
    const redirect = encodeURIComponent(current)
    window.location.href = `/login?redirect=${redirect}`
  }
}

export default request
