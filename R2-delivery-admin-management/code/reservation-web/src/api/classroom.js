import request from '@/utils/request'

/**
 * 教室管理端接口封装（/api/classroom/manage，管理员专属）
 */

/** 管理端：教室分页列表 + 多条件搜索（keyword/building/type/status） */
export function pageClassrooms(params) {
  return request.get('/classroom/manage', { params })
}

/** 管理端：新增教室 */
export function createClassroom(data) {
  return request.post('/classroom/manage', data)
}

/** 管理端：编辑教室 */
export function updateClassroom(data) {
  return request.put('/classroom/manage', data)
}

/** 管理端：删除教室（存在预约记录时后端返回 400） */
export function deleteClassroom(id) {
  return request.delete(`/classroom/manage/${id}`)
}

/** 管理端：启用/停用教室（status: 0-停用, 1-可用） */
export function updateClassroomStatus(id, status) {
  return request.put(`/classroom/manage/${id}/status`, { status })
}

/** 管理端：批量启用/停用教室（ids + status） */
export function batchClassroomStatus(ids, status) {
  return request.post('/classroom/manage/batch-status', { ids, status })
}
