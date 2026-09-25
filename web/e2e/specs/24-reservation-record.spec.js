/**
 * 管理端 24 —— 预约记录（/admin/records，ReservationRecord.vue）
 *
 * 覆盖：多条件筛选（状态 / 日期范围 / 教室 / 关键词）、Excel 导出（真实触发浏览器下载且 .xlsx）。
 */
import { test, expect } from '@playwright/test'
import { loginAs, apiLogin } from '../helpers/auth'
import { e2ePurpose, futureDate } from '../helpers/data'
import { seedReservation, fetchClassroomMap, exportReservationsApi, uniqueTag } from '../helpers/admin'
import { searchItem, rowOf, pickOption, fillDateRange, expectMessage } from '../helpers/ui'

const CLASSROOM_IDS = [6, 7, 8]
const DATES = [futureDate(7), futureDate(8), futureDate(9)]

let adminToken = null
let studentToken = null
let roomMap = null

/** 教室筛选下拉的展示文案（与 ReservationRecord.vue 的 :label 拼接方式一致） */
function roomLabel(room) {
  return `${room.name}（${room.building}-${room.roomNo}）`
}

async function seedRecord(page, tag) {
  const purpose = e2ePurpose(`${tag}-${uniqueTag('')}`)
  const seeded = await seedReservation(page.request, {
    studentToken,
    adminToken,
    classroomIds: CLASSROOM_IDS,
    dates: DATES,
    purpose,
    approve: true
  })
  return { ...seeded, purpose, room: roomMap.get(seeded.classroomId) }
}

test.describe('管理端-预约记录（ReservationRecord）', () => {
  test.beforeEach(async ({ page }) => {
    const data = await loginAs(page, { who: 'admin', path: '/admin/records' })
    adminToken = data.token
    studentToken = (await apiLogin(page.request, 'student')).token
    roomMap = await fetchClassroomMap(page.request, adminToken)
    await expect(page.locator('tr.el-table__row').first()).toBeVisible()
  })

  test('多条件筛选：状态 + 日期范围 + 教室 + 关键词', async ({ page }) => {
    const rec = await seedRecord(page, '记录筛选')

    await searchItem(page, '用户').getByRole('textbox').fill('zhangsan')
    await pickOption(page, searchItem(page, '状态'), '已通过')
    await pickOption(page, searchItem(page, '教室'), roomLabel(rec.room))
    await fillDateRange(page, rec.date, rec.date)
    await page.getByRole('button', { name: '查询' }).click()

    const row = rowOf(page, rec.purpose)
    await expect(row, '四条件组合查询应命中自造记录').toBeVisible()
    await expect(row.locator('.el-tag', { hasText: '已通过' })).toBeVisible()
    await expect(row).toContainText(rec.date)
    await expect(row).toContainText(`${rec.startTime}-${rec.endTime}`)
    await expect(row).toContainText('zhangsan')

    // 状态筛选生效：结果集不应出现其他状态
    await expect(page.locator('tr.el-table__row .el-tag', { hasText: '待审核' })).toHaveCount(0)
    await expect(page.locator('tr.el-table__row .el-tag', { hasText: '已驳回' })).toHaveCount(0)

    // 教室筛选生效：其它教室的记录不应出现（用种子教室 C301 的记录做反向验证）
    await expect(rowOf(page, 'C301')).toHaveCount(0)

    // 重置后条件清空
    await page.getByRole('button', { name: '重置' }).click()
    await expect(searchItem(page, '用户').getByRole('textbox')).toHaveValue('')
    await expect(page.locator('tr.el-table__row').first()).toBeVisible()
  })

  test('Excel 导出：按筛选条件触发下载且文件名为 xlsx', async ({ page }) => {
    const rec = await seedRecord(page, '记录导出')

    // 先收窄筛选范围（也顺带验证「按条件导出」）
    await pickOption(page, searchItem(page, '状态'), '已通过')
    await pickOption(page, searchItem(page, '教室'), roomLabel(rec.room))
    await fillDateRange(page, rec.date, rec.date)
    await page.getByRole('button', { name: '查询' }).click()
    await expect(rowOf(page, rec.purpose)).toBeVisible()

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('button', { name: '导出 Excel' }).click()
    ])

    const filename = download.suggestedFilename()
    expect(filename, '导出文件名应为 .xlsx').toMatch(/\.xlsx$/)
    expect(filename, '导出文件名应带业务前缀').toMatch(/^预约记录_/)
    expect(await download.path(), '下载应成功落盘').toBeTruthy()
    await expectMessage(page, '导出成功，请查看下载文件')

    // 接口交叉复核：同条件导出确为 xlsx 文件流（非 JSON 错误体）
    const api = await exportReservationsApi(page.request, adminToken, {
      status: 1,
      classroomId: rec.classroomId,
      startDate: rec.date,
      endDate: rec.date
    })
    expect(api.status, '导出接口应返回 200').toBe(200)
    expect(api.contentType, `导出应返回文件流而非 JSON：${api.contentType}`).not.toContain('json')
    expect(api.bytes, '导出文件不应为空').toBeGreaterThan(0)
  })
})