/**
 * 管理端 22 —— 预约审核（/admin/audits，AuditManage.vue）
 *
 * 覆盖：全量申请查询与筛选（状态/日期范围/关键词）、一键通过、驳回（备注必填 + 快捷原因回填）、
 *       批量通过、批量驳回（仅待审核参与，混入已通过记录不被改动）、审核后状态/备注正确。
 *
 * 定位约定：所有自造记录用途带 'E2E自动化-' 前缀，用例按用途文案定位行，
 *          禁止按行号/“第一条”定位（其他任务同时在造待审核数据）。
 */
import { test, expect } from '@playwright/test'
import { loginAs, apiLogin } from '../helpers/auth'
import { e2ePurpose, futureDate } from '../helpers/data'
import { uniqueTag, seedReservation, findManageByPurpose } from '../helpers/admin'
import { searchItem, rowOf, pickOption, fillDateRange, setPageSize, expectMessage } from '../helpers/ui'

/** 本任务分配到的资源，避免与其他并行任务抢同一教室/日期 */
const CLASSROOM_IDS = [6, 7, 8]
const DATES = [futureDate(7), futureDate(8), futureDate(9)]

let adminToken = null
let studentToken = null
/**
 * 本用例内已占用的时段。冲突检测只认「已通过」记录，连续创建的多条待审核记录不会被拦住，
 * 可能落到同一时段；而批量审核会校验同批记录时段重叠并整批拒绝，故需自行避让。
 */
let usedSlots = []

/** 打开筛选：每页 50 条 + 日期范围（必要时加状态），保证自造记录稳定落在当前页 */
async function openFiltered(page, { status } = {}) {
  await setPageSize(page, '50条/页')
  if (status !== undefined) {
    await pickOption(page, searchItem(page, '状态'), status)
  }
  await fillDateRange(page, DATES[0], DATES[2])
  await page.getByRole('button', { name: '查询' }).click()
}

async function seedPending(page, tag) {
  const purpose = e2ePurpose(`${tag}-${uniqueTag('')}`)
  const seeded = await seedReservation(page.request, {
    studentToken,
    adminToken,
    classroomIds: CLASSROOM_IDS,
    dates: DATES,
    purpose,
    avoid: usedSlots
  })
  usedSlots.push(seeded)
  return { ...seeded, purpose }
}

async function seedApproved(page, tag) {
  const purpose = e2ePurpose(`${tag}-${uniqueTag('')}`)
  const seeded = await seedReservation(page.request, {
    studentToken,
    adminToken,
    classroomIds: CLASSROOM_IDS,
    dates: DATES,
    purpose,
    approve: true,
    avoid: usedSlots
  })
  usedSlots.push(seeded)
  return { ...seeded, purpose }
}

async function selectRow(page, purpose) {
  const row = rowOf(page, purpose)
  await expect(row).toBeVisible()
  await row.locator('.el-checkbox').click()
  await expect(row.locator('.el-checkbox').first()).toHaveClass(/is-checked/)
}

test.describe('管理端-预约审核（AuditManage）', () => {
  test.beforeEach(async ({ page }) => {
    usedSlots = []
    const data = await loginAs(page, { who: 'admin', path: '/admin/audits' })
    adminToken = data.token
    studentToken = (await apiLogin(page.request, 'student')).token
  })

  test('全量申请查询与筛选：状态 + 日期范围 + 关键词', async ({ page }) => {
    const seeded = await seedPending(page, '审核筛选')
    await openFiltered(page, { status: '待审核' })

    const row = rowOf(page, seeded.purpose)
    await expect(row, '自造待审核记录应出现在筛选结果中').toBeVisible()
    await expect(row.locator('.el-tag', { hasText: '待审核' })).toBeVisible()
    await expect(row).toContainText('zhangsan')

    // 关键词（用户账号）再筛一层，仍应命中
    await searchItem(page, '关键词').getByRole('textbox').fill('zhangsan')
    await page.getByRole('button', { name: '查询' }).click()
    await expect(rowOf(page, seeded.purpose)).toBeVisible()

    // 关键词改成不存在的账号 → 命中为空
    await searchItem(page, '关键词').getByRole('textbox').fill('no_such_user_zzz')
    await page.getByRole('button', { name: '查询' }).click()
    await expect(page.getByText('暂无符合条件的预约记录')).toBeVisible()

    // 重置后恢复全量
    await page.getByRole('button', { name: '重置' }).click()
    await expect(page.locator('tr.el-table__row').first()).toBeVisible()
  })

  test('一键通过：状态变为已通过', async ({ page }) => {
    const seeded = await seedPending(page, '一键通过')
    await openFiltered(page, { status: '待审核' })

    const row = rowOf(page, seeded.purpose)
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '通过' }).click()

    const box = page.locator('.el-message-box')
    await expect(box).toContainText('通过确认')
    await box.getByRole('button', { name: '确定通过' }).click()
    await expectMessage(page, '审核通过')

    // 审核后该记录不再属于「待审核」结果集
    await expect(rowOf(page, seeded.purpose)).toHaveCount(0)
    // 切到「已通过」筛选，行出现且状态/操作列正确
    await pickOption(page, searchItem(page, '状态'), '已通过')
    await page.getByRole('button', { name: '查询' }).click()
    const updated = rowOf(page, seeded.purpose)
    await expect(updated).toBeVisible()
    await expect(updated.locator('.el-tag', { hasText: '已通过' })).toBeVisible()
    await expect(updated).toContainText('已处理')

    // 接口交叉复核：该记录确为已通过（不是仅前端渲染）
    const record = await findManageByPurpose(page.request, adminToken, seeded.purpose, {
      status: 1,
      startDate: DATES[0],
      endDate: DATES[2]
    })
    expect(record && record.status, `记录 ${seeded.purpose} 状态应为 1`).toBe(1)
  })

  test('驳回：备注必填校验 + 快捷原因回填', async ({ page }) => {
    const seeded = await seedPending(page, '驳回必填')
    await openFiltered(page, { status: '待审核' })

    const row = rowOf(page, seeded.purpose)
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '驳回' }).click()

    const dialog = page.locator('.el-dialog')
    await expect(dialog.locator('.el-dialog__title')).toHaveText('驳回预约')
    const textarea = dialog.getByPlaceholder('驳回必须填写审核备注')
    const confirmReject = dialog.getByRole('button', { name: '确认驳回' })
    await expect(confirmReject, '未填备注时确认驳回应不可点').toBeDisabled()

    // 快捷原因一键回填
    await dialog.locator('.reason-tag', { hasText: '时间冲突' }).click()
    await expect(textarea).toHaveValue('时间冲突')
    await expect(confirmReject).toBeEnabled()

    await confirmReject.click()
    const box = page.locator('.el-message-box')
    await expect(box).toContainText('驳回确认')
    await box.getByRole('button', { name: '确定驳回' }).click()
    await expectMessage(page, '已驳回')

    // 切到「已驳回」筛选查看终态与备注
    await pickOption(page, searchItem(page, '状态'), '已驳回')
    await page.getByRole('button', { name: '查询' }).click()
    const updated = rowOf(page, seeded.purpose)
    await expect(updated).toBeVisible()
    await expect(updated.locator('.el-tag', { hasText: '已驳回' })).toBeVisible()
    await expect(updated, '审核备注应回写为所选快捷原因').toContainText('时间冲突')
  })

  test('批量通过：仅待审核记录生效', async ({ page }) => {
    const a = await seedPending(page, '批量通过')
    const b = await seedPending(page, '批量通过')
    await openFiltered(page, { status: '待审核' })

    await selectRow(page, a.purpose)
    await selectRow(page, b.purpose)

    const batchPass = page.getByRole('button', { name: /批量通过/ })
    await expect(batchPass).toBeEnabled()
    await batchPass.click()
    const box = page.locator('.el-message-box')
    await expect(box).toContainText('批量通过确认')
    await box.getByRole('button', { name: '确定通过' }).click()
    await expectMessage(page, '批量通过成功')

    // 待审核结果集里两条都应消失
    await expect(rowOf(page, a.purpose)).toHaveCount(0)
    await expect(rowOf(page, b.purpose)).toHaveCount(0)
    // 切到「已通过」筛选，两条都应为已通过
    await pickOption(page, searchItem(page, '状态'), '已通过')
    await page.getByRole('button', { name: '查询' }).click()
    await expect(rowOf(page, a.purpose).locator('.el-tag', { hasText: '已通过' })).toBeVisible()
    await expect(rowOf(page, b.purpose).locator('.el-tag', { hasText: '已通过' })).toBeVisible()
  })

  test('批量驳回：已通过记录不参与（不被改动）', async ({ page }) => {
    const pending = await seedPending(page, '批量驳回-待审核')
    const approved = await seedApproved(page, '批量驳回-已通过')
    // 不筛状态，让「待审核 + 已通过」混合出现
    await openFiltered(page)

    await selectRow(page, pending.purpose)
    await selectRow(page, approved.purpose)

    await page.getByRole('button', { name: /批量驳回/ }).click()
    const dialog = page.locator('.el-dialog')
    await expect(dialog.locator('.el-dialog__title')).toHaveText('批量驳回预约')
    await dialog.locator('.reason-tag', { hasText: '教室维护' }).click()
    await dialog.getByRole('button', { name: '确认驳回' }).click()

    const box = page.locator('.el-message-box')
    await expect(box).toContainText('驳回确认')
    await box.getByRole('button', { name: '确定驳回' }).click()
    // 后端只对 1 条待审核生效
    await expectMessage(page, '批量驳回成功，共 1 条')

    await expect(rowOf(page, pending.purpose).locator('.el-tag', { hasText: '已驳回' })).toBeVisible()
    await expect(rowOf(page, approved.purpose).locator('.el-tag', { hasText: '已通过' })).toBeVisible()
    // 接口交叉复核：已通过记录状态与审核备注均未被改动
    const record = await findManageByPurpose(page.request, adminToken, approved.purpose, {
      status: 1,
      startDate: DATES[0],
      endDate: DATES[2]
    })
    expect(record && record.status, '已通过记录不应被批量驳回改动').toBe(1)
    expect(record.auditRemark ?? null, '已通过记录不应被写入驳回备注').toBeNull()
  })
})