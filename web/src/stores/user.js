import { defineStore } from 'pinia'
import { login as loginApi, logout as logoutApi, getInfo as getInfoApi } from '@/api/user'
import { getToken, getStoredUser, saveAuth, clearAuth } from '@/utils/auth'
import { clearAiProbe } from '@/utils/aiProbe'

/**
 * 用户状态管理（Pinia）
 * token 与用户信息持久化到 localStorage，刷新不丢失
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    userInfo: getStoredUser(),
    /**
     * AI 开关（登录接口随 LoginVO 下发）。
     * null = 未知（未登录 / 刷新后尚未登录），此时 AI 入口按「探测」决定显隐；
     * false = 后端明确关闭，前端直接隐藏 AI 入口、不再探测 /ai/recommend。
     * 不持久化：每次登录重新同步，避免动态开关（ai_config.ai_enable）被本地旧值掩盖。
     */
    aiEnabled: null
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    role: (state) => (state.userInfo ? state.userInfo.role : null)
  },

  actions: {
    /** 登录：调用接口成功后保存 Token 与用户信息，并记录后端下发的 AI 开关 */
    async login(form) {
      const res = await loginApi(form)
      saveAuth(res.data.token, res.data.user)
      this.token = res.data.token
      this.userInfo = res.data.user
      this.aiEnabled = typeof res.data.aiEnabled === 'boolean' ? res.data.aiEnabled : null
      return res.data.user
    },

    /** 刷新当前用户信息 */
    async fetchInfo() {
      const res = await getInfoApi()
      this.userInfo = res.data
      saveAuth(this.token, res.data)
      return res.data
    },

    /**
     * 退出登录：先请求后端删除 Redis 会话（登出后 Token 立即失效），无论成败都清除本地登录态。
     * 注意：后端注销请求需在 clearAuth 之前发起，请求拦截器才能携带当前 Token。
     */
    async logout() {
      try {
        await logoutApi()
      } catch {
        // 后端不可用 / Redis 降级时不阻断退出，本地照常清除
      } finally {
        clearAuth()
        this.token = ''
        this.userInfo = null
        this.aiEnabled = null
        // N5：登出清理 AI 探测缓存（按 userId 分键，避免切换账号拿到上一个用户的推荐）
        clearAiProbe()
      }
    }
  }
})
