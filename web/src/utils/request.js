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
      // silent 请求（如退出登录）不弹错误提示，仅拒绝
      if (!response.config.silent) ElMessage.error(res.message || '无权限访问该功能')
      return Promise.reject(new Error(res.message || '无权限访问'))
    }
    if (!response.config.silent) ElMessage.error(res.message || '操作失败，请稍后重试')
    return Promise.reject(new Error(res.message || '操作失败'))
  },
  (error) => {
    const status = error.response && error.response.status
    // silent 请求（如退出登录）失败时不弹错误提示，交由调用方静默处理
    const silent = error.config && error.config.silent
    // M13 修复：Blob 响应错误（如导出命中上限返回 400）在任何状态码下都先解析 JSON 错误信息；
    // 此前仅 403 分支解析 Blob，400 分支取 error.response.data.message 得到 undefined，
    // 用户只看到通用"网络异常"，不知道要"缩小筛选范围"
    const isBlobError =
      error.config &&
      error.config.responseType === 'blob' &&
      error.response &&
      error.response.data
    if (isBlobError) {
      readBlobMessage(error.response.data).then((msg) => {
        if (status === 401) {
          // blob 请求同样处理登录过期：清登录态并跳转
          clearAuth()
          redirectToLogin()
        } else if (!silent) {
          ElMessage.error(msg || '操作失败，请稍后重试')
        }
      })
      return Promise.reject(error)
    }
    if (status === 401) {
      // 后端拦截器返回的 401：清登录态并跳转
      clearAuth()
      redirectToLogin()
    } else if (status === 403) {
      if (!silent) ElMessage.error('无权限访问该功能')
    } else if (!silent) {
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
