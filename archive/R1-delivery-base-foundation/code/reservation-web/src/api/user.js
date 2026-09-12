import request from '@/utils/request'

/** 登录：POST /api/user/login */
export function login(data) {
  return request.post('/user/login', data)
}

/** 学生注册：POST /api/user/register */
export function register(data) {
  return request.post('/user/register', data)
}

/** 当前用户信息：GET /api/user/info */
export function getInfo() {
  return request.get('/user/info')
}
