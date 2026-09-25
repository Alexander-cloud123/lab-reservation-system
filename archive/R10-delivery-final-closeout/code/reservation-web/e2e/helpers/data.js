/**
 * E2E 测试数据约定与日期工具
 *
 * 命名前缀：所有 E2E 造的预约用途一律以 E2E_PURPOSE_PREFIX 开头，
 * 便于跑完统一用 SQL 清理（见本文件尾部注释），不与人工/演示数据混淆。
 */
import dayjs from 'dayjs'

/** 测试预约用途前缀（清理依据，勿改） */
export const E2E_PURPOSE_PREFIX = 'E2E自动化-'

/** 生成带前缀的测试用途 */
export function e2ePurpose(tag) {
  return `${E2E_PURPOSE_PREFIX}${tag}`
}

/**
 * 教室 ID 分配表（database/init_db.sql 固定 12 间，id 1-12）
 * 按用例分组占用，避免两条用例抢同一间教室同一时段造成互相干扰。
 *
 * key 即 room_no（与库内一致），value 只放「稳定且可断言」的字段：
 * - id / type 由 init_db.sql 固定，可安全引用
 * - 教室名称、楼栋等展示字段一律以接口返回为准，不在此维护（库内值会变，硬编码必然过期）
 * 因此断言写 CLASSROOMS.C101.id，不要写 name / building。
 */
export const CLASSROOMS = {
  A101: { id: 1, type: 1 },
  A102: { id: 2, type: 1 },
  A201: { id: 3, type: 2 },
  A301: { id: 4, type: 3 },
  B101: { id: 5, type: 2 },
  B102: { id: 6, type: 2 },
  B201: { id: 7, type: 3 },
  B301: { id: 8, type: 1 },
  C101: { id: 9, type: 1 },
  C201: { id: 10, type: 2 },
  C301: { id: 11, type: 3 },
  C401: { id: 12, type: 1 }
}

/** 学生端页面可见的教室类型下拉选项（前端字典文案） */
export const TYPE_LABELS = { 1: '普通教室', 2: '实验室', 3: '机房' }

/** 预约状态文案（前端字典） */
export const STATUS_LABELS = { 0: '待审核', 1: '已通过', 2: '已驳回', 3: '已取消' }

/**
 * 相对今天的日期（yyyy-MM-dd）。
 * 一律用"未来日期"造预约：后端要求预约开始时间必须晚于当前时间，写死日期的用例会随时间失效。
 */
export function futureDate(daysFromToday) {
  return dayjs().add(daysFromToday, 'day').format('YYYY-MM-DD')
}

/** 今天（yyyy-MM-dd） */
export function today() {
  return dayjs().format('YYYY-MM-DD')
}

/** 当前时间 + n 分钟（HH:mm），用于取消时限这类"贴近当前时刻"的场景 */
export function timeFromNow(minutes) {
  return dayjs().add(minutes, 'minute').format('HH:mm')
}

/** 常用时段（HH:mm 两元素数组） */
export const SLOTS = {
  morning: ['08:00', '10:00'],
  noon: ['10:00', '12:00'],
  afternoon: ['14:00', '16:00'],
  evening: ['16:00', '18:00']
}

/**
 * 跑完清理测试数据（在本机 Docker 环境执行，非用例的一部分）。
 *
 * 注意：docker exec 管道会把中文 LIKE 模式打成乱码（实测 like '%自动化%' 命中 0 行），
 * 因此这里只用前缀的 ASCII 部分 `E2E%` 匹配，不要写中文：
 *   docker exec tmp-res-mysql mysql -uroot -proot -e "use reservation; delete from reservation where purpose like 'E2E%';"
 *
 * 注册流程用例会遗留测试账号，需一并清理（种子账号 admin/zhangsan/lisi/wangwu/zhaoliu 不受影响）：
 *   docker exec tmp-res-mysql mysql -uroot -proot -e "use reservation; delete from user_favorite where user_id in (select id from sys_user where username like 'e2e%'); delete from reservation where user_id in (select id from sys_user where username like 'e2e%'); delete from sys_user where username like 'e2e%';"
 *
 * 若后端与库在其他机器，请按实际容器名/连接参数替换。
 */