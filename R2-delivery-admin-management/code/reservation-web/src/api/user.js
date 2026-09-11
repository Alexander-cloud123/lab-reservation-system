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

/** 管理端：用户分页列表 + 多条件查询（keyword/role/status） */
export function pageUsers(params) {
  return request.get('/user/manage', { params })
}

/** 管理端：启用/禁用用户（status: 0-禁用, 1-正常） */
export function updateUserStatus(id, status) {
  return request.put(`/user/manage/${id}/status`, { status })
}

/** 管理端：重置用户密码为默认密码 123456 */
export function resetUserPassword(id) {
  return request.put(`/user/manage/${id}/password`)
}
