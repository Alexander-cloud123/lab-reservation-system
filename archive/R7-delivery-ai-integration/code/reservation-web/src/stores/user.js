import { defineStore } from 'pinia'
import { login as loginApi, getInfo as getInfoApi } from '@/api/user'
import { getToken, getStoredUser, saveAuth, clearAuth } from '@/utils/auth'

/**
 * 用户状态管理（Pinia）
 * token 与用户信息持久化到 localStorage，刷新不丢失
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    userInfo: getStoredUser()
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    role: (state) => (state.userInfo ? state.userInfo.role : null)
  },

  actions: {
    /** 登录：调用接口成功后保存 Token 与用户信息 */
    async login(form) {
      const res = await loginApi(form)
      saveAuth(res.data.token, res.data.user)
      this.token = res.data.token
      this.userInfo = res.data.user
      return res.data.user
    },

    /** 刷新当前用户信息 */
    async fetchInfo() {
      const res = await getInfoApi()
      this.userInfo = res.data
      saveAuth(this.token, res.data)
      return res.data
    },

    /** 退出登录：清除本地登录态 */
    logout() {
      clearAuth()
      this.token = ''
      this.userInfo = null
    }
  }
})
