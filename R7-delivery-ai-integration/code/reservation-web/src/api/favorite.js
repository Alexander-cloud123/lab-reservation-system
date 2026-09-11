import request from '@/utils/request'

/**
 * 收藏模块接口封装（R4 收藏全链路）
 * 学生端：教室详情页收藏/取消（toggle）、个人中心常用教室列表
 */

/** 收藏/取消收藏（toggle）：POST /api/favorite/{classroomId}，返回 data=是否已收藏 */
export function toggleFavorite(classroomId) {
  return request.post(`/favorite/${classroomId}`)
}

/** 我的收藏列表：GET /api/favorite/list（含教室信息，按收藏时间倒序） */
export function getFavoriteList() {
  return request.get('/favorite/list')
}
