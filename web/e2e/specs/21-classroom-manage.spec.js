/**
 * 管理端 21 —— 教室资源管理（/admin/classrooms，ClassroomManage.vue）
 *
 * 覆盖：列表渲染、多条件搜索（关键词/楼栋/类型）、新增（必填 + 容量数值校验）、
 *       编辑回显、启用/停用、批量启用/停用、删除自建 E2E 教室、有预约记录的种子教室删除被拒。
 *
 * 数据约定（避免与其他并行任务互撞）：
 *   - 自建教室一律 'E2E-教室-xxx'，用例结束用接口删除
 *   - 不动 12 间种子教室（删除保护用例只“尝试删除”A101，预期后端 400 拒绝，不产生实际删除）
 */
import { test, expect } from '@playwright/test'
import { loginAs } from '../helpers/auth'
import { expectOk } from '../helpers/api'
import { createClassroomApi, deleteClassroomApi, listClassroomsManage, uniqueTag } from '../helpers/admin'
import { formItem, searchItem, rowOf, pickOption, expectMessage } from '../helpers/ui'

let token = null
let createdRoomIds = []
let roomSeq = 0

async function createRoom(page, over = {}) {
  const name = over.name || `E2E-教室-${uniqueTag('')}`
  const dto = {
    name,
    building: over.building || 'E2E楼',
    roomNo: over.roomNo || `E2E-${Date.now().toString(36)}-${++roomSeq}`,
    type: over.type ?? 1,
    capacity: over.capacity ?? 40,
    equipment: 'E2E 自动化用例',
    description: 'E2E'
  }
  const id = expectOk(await createClassroomApi(page.request, token, dto), `新增教室「${name}」`)
  createdRoomIds.push(id)
  return { id, ...dto }
}

async function searchKeyword(page, keyword) {
  await searchItem(page, '关键词').getByRole('textbox').fill(keyword)
  await page.getByRole('button', { name: '查询' }).click()
}

test.describe('管理端-教室资源管理（ClassroomManage）', () => {
  test.beforeEach(async ({ page }) => {
    createdRoomIds = []
    const data = await loginAs(page, { who: 'admin', path: '/admin/classrooms' })
    token = data.token
    await expect(page.locator('tr.el-table__row').first()).toBeVisible()
  })

  test.afterEach(async ({ page }) => {
    // 收尾：删除本次自建的 E2E 教室（尽力而为，失败不影响用例结论）
    while (createdRoomIds.length) {
      await deleteClassroomApi(page.request, token, createdRoomIds.pop())
    }
  })

  test('列表渲染 + 关键词搜索（名称/编号）', async ({ page }) => {
    await expect(page.locator('.page-title', { hasText: '教室管理' })).toBeVisible()
    await expect(page.locator('tr.el-table__row').first()).toBeVisible()
    await expect(page.locator('.el-pagination__total')).toHaveText(/共\s*\d+\s*条/)

    // 关键词命中编号 A101（种子教室 A101多媒体教室）
    await searchKeyword(page, 'A101')
    const row = rowOf(page, 'A101').first()
    await expect(row).toBeVisible()
    await expect(row).toContainText('信息楼')

    await page.getByRole('button', { name: '重置' }).click()
    await expect(page.locator('tr.el-table__row').first()).toBeVisible()
  })

  test('多条件搜索：关键词 / 楼栋 / 类型', async ({ page }) => {
    const roomA = await createRoom(page, { name: `E2E-教室-搜索甲-${uniqueTag('')}`, building: 'E2E甲栋', type: 1 })
    const roomB = await createRoom(page, { name: `E2E-教室-搜索乙-${uniqueTag('')}`, building: 'E2E乙栋', type: 3 })

    // ① 关键词
    await searchKeyword(page, roomA.name)
    await expect(rowOf(page, roomA.name)).toBeVisible()
    await expect(rowOf(page, roomB.name)).toHaveCount(0)

    // ② 楼栋
    await page.getByRole('button', { name: '重置' }).click()
    await pickOption(page, searchItem(page, '楼栋'), 'E2E乙栋')
    await page.getByRole('button', { name: '查询' }).click()
    await expect(rowOf(page, roomB.name)).toBeVisible()
    await expect(rowOf(page, roomA.name)).toHaveCount(0)

    // ③ 楼栋 + 类型（机房）→ 命中乙；类型改实验室 → 乙被排除
    await pickOption(page, searchItem(page, '类型'), '机房')
    await page.getByRole('button', { name: '查询' }).click()
    await expect(rowOf(page, roomB.name)).toBeVisible()

    await pickOption(page, searchItem(page, '类型'), '实验室')
    await page.getByRole('button', { name: '查询' }).click()
    await expect(rowOf(page, roomB.name)).toHaveCount(0)
  })

  test('新增教室：必填校验 + 新增成功', async ({ page }) => {
    await page.getByRole('button', { name: '新增教室' }).click()
    const dialog = page.locator('.el-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('.el-dialog__title')).toHaveText('新增教室')

    // 空表单直接提交 → 逐条必填校验
    await dialog.getByRole('button', { name: '确定' }).click()
    for (const msg of ['请输入教室名称', '请输入所属楼栋', '请输入教室编号', '请选择教室类型']) {
      await expect(dialog.getByText(msg), `应提示「${msg}」`).toBeVisible()
    }

    const name = `E2E-教室-新增-${uniqueTag('')}`
    await formItem(dialog, '教室名称').getByRole('textbox').fill(name)
    await formItem(dialog, '所属楼栋').getByRole('textbox').fill('E2E楼')
    await formItem(dialog, '教室编号').getByRole('textbox').fill('E2E-N1')
    await pickOption(page, formItem(dialog, '类型'), '实验室')
    await formItem(dialog, '容纳人数').locator('input').fill('66')
    await dialog.getByRole('button', { name: '确定' }).click()

    await expectMessage(page, '新增成功')
    await expect(dialog).toBeHidden()

    await searchKeyword(page, name)
    const row = rowOf(page, name)
    await expect(row).toBeVisible()
    await expect(row).toContainText('66')
    await expect(row.locator('.el-tag', { hasText: '实验室' })).toBeVisible()

    // 取回 id 以便收尾删除
    const data = expectOk(await listClassroomsManage(page.request, token, { page: 1, size: 50, keyword: name }), '按名称查教室')
    createdRoomIds.push(data.records[0].id)
  })

  test('新增教室：容量为数值且被夹在 [1, 10000] 区间内', async ({ page }) => {
    await page.getByRole('button', { name: '新增教室' }).click()
    const dialog = page.locator('.el-dialog')
    await expect(dialog).toBeVisible()
    const capacityInput = formItem(dialog, '容纳人数').locator('input')

    await expect(capacityInput).toHaveValue('1')
    // 低于下限 → 夹到 1；高于上限 → 夹到 10000
    await capacityInput.fill('0')
    await capacityInput.blur()
    await expect(capacityInput, '容量低于 1 应被夹到 1').toHaveValue('1')

    await capacityInput.fill('20000')
    await capacityInput.blur()
    await expect(capacityInput, '容量超过 10000 应被夹到 10000').toHaveValue('10000')

    // 加减按钮按步长（1）调整
    await capacityInput.fill('5')
    await capacityInput.blur()
    await formItem(dialog, '容纳人数').locator('.el-input-number__increase').click()
    await formItem(dialog, '容纳人数').locator('.el-input-number__increase').click()
    await expect(capacityInput).toHaveValue('7')
    await formItem(dialog, '容纳人数').locator('.el-input-number__decrease').click()
    await expect(capacityInput).toHaveValue('6')

    await dialog.getByRole('button', { name: '取消' }).click()
    await expect(dialog).toBeHidden()
  })

  test('编辑教室：表单回显 + 保存后列表生效', async ({ page }) => {
    const room = await createRoom(page, {
      name: `E2E-教室-编辑-${uniqueTag('')}`,
      building: 'E2E编辑楼',
      roomNo: 'E2E-ED',
      type: 1,
      capacity: 40
    })

    await searchKeyword(page, room.name)
    const row = rowOf(page, room.name)
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '编辑' }).click()

    const dialog = page.locator('.el-dialog')
    await expect(dialog.locator('.el-dialog__title')).toHaveText('编辑教室')
    await expect(formItem(dialog, '教室名称').getByRole('textbox')).toHaveValue(room.name)
    await expect(formItem(dialog, '所属楼栋').getByRole('textbox')).toHaveValue('E2E编辑楼')
    await expect(formItem(dialog, '教室编号').getByRole('textbox')).toHaveValue('E2E-ED')
    await expect(formItem(dialog, '容纳人数').locator('input')).toHaveValue('40')

    await formItem(dialog, '容纳人数').locator('input').fill('88')
    await pickOption(page, formItem(dialog, '类型'), '机房')
    await dialog.getByRole('button', { name: '确定' }).click()

    await expectMessage(page, '修改成功')
    const updated = rowOf(page, room.name)
    await expect(updated).toContainText('88')
    await expect(updated.locator('.el-tag', { hasText: '机房' })).toBeVisible()
  })

  test('启用/停用切换（自建 E2E 教室）', async ({ page }) => {
    const room = await createRoom(page, { name: `E2E-教室-状态-${uniqueTag('')}`, roomNo: 'E2E-ST' })
    await searchKeyword(page, room.name)

    const row = () => rowOf(page, room.name)
    await expect(row().locator('.el-tag', { hasText: '可用' })).toBeVisible()

    await row().getByRole('button', { name: '停用' }).click()
    const box = page.locator('.el-message-box')
    await expect(box).toContainText('停用确认')
    await box.getByRole('button', { name: '确定停用' }).click()
    await expectMessage(page, '停用成功')
    await expect(row().locator('.el-tag', { hasText: '停用' })).toBeVisible()

    await row().getByRole('button', { name: '启用' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '确定启用' }).click()
    await expectMessage(page, '启用成功')
    await expect(row().locator('.el-tag', { hasText: '可用' })).toBeVisible()
  })

  test('批量启用/停用（勾选多条自建 E2E 教室）', async ({ page }) => {
    const prefix = `E2E-教室-批量-${uniqueTag('')}`
    const roomA = await createRoom(page, { name: `${prefix}-1`, roomNo: 'E2E-B1' })
    const roomB = await createRoom(page, { name: `${prefix}-2`, roomNo: 'E2E-B2' })

    await searchKeyword(page, prefix)
    // 不断言「总行数 == N」（并行任务可能同时写入），只断言自建的两条都在结果中
    await expect(rowOf(page, roomA.name)).toBeVisible()
    await expect(rowOf(page, roomB.name)).toBeVisible()

    const batchDisable = page.getByRole('button', { name: /批量停用/ })
    const batchEnable = page.getByRole('button', { name: /批量启用/ })
    await expect(batchDisable).toBeDisabled()
    await expect(batchEnable).toBeDisabled()

    await rowOf(page, roomA.name).locator('.el-checkbox').click()
    await rowOf(page, roomB.name).locator('.el-checkbox').click()
    await expect(batchDisable).toBeEnabled()

    await batchDisable.click()
    const box = page.locator('.el-message-box')
    await expect(box).toContainText('批量停用确认')
    await box.getByRole('button', { name: '确定停用' }).click()
    await expectMessage(page, '批量停用成功')
    await expect(rowOf(page, roomA.name).locator('.el-tag', { hasText: '停用' })).toBeVisible()
    await expect(rowOf(page, roomB.name).locator('.el-tag', { hasText: '停用' })).toBeVisible()

    // 列表刷新后选择被清空，需重新勾选
    await rowOf(page, roomA.name).locator('.el-checkbox').click()
    await rowOf(page, roomB.name).locator('.el-checkbox').click()
    await batchEnable.click()
    await page.locator('.el-message-box').getByRole('button', { name: '确定启用' }).click()
    await expectMessage(page, '批量启用成功')
    await expect(rowOf(page, roomA.name).locator('.el-tag', { hasText: '可用' })).toBeVisible()
    await expect(rowOf(page, roomB.name).locator('.el-tag', { hasText: '可用' })).toBeVisible()
  })

  test('删除自建 E2E 教室（二次确认后成功）', async ({ page }) => {
    const room = await createRoom(page, { name: `E2E-教室-删除-${uniqueTag('')}`, roomNo: 'E2E-DEL' })
    await searchKeyword(page, room.name)
    const row = rowOf(page, room.name)
    await expect(row).toBeVisible()

    await row.getByRole('button', { name: '删除' }).click()
    const box = page.locator('.el-message-box')
    await expect(box).toContainText('删除确认')
    await expect(box).toContainText('确定要删除教室')
    await box.getByRole('button', { name: '确定删除' }).click()

    await expectMessage(page, '删除成功')
    await expect(row).toHaveCount(0)
    await expect(page.getByText('没有找到符合条件的教室')).toBeVisible()

    // 已删除，移出收尾清单
    const i = createdRoomIds.indexOf(room.id)
    if (i >= 0) createdRoomIds.splice(i, 1)
  })

  test('存在预约记录的教室（A101）删除被拒并有提示', async ({ page }) => {
    await searchKeyword(page, 'A101')
    const row = rowOf(page, 'A101').first()
    await expect(row).toBeVisible()

    await row.getByRole('button', { name: '删除' }).click()
    const box = page.locator('.el-message-box')
    await box.getByRole('button', { name: '确定删除' }).click()

    await expectMessage(page, '该教室存在预约记录，禁止删除')
    await expect(row, '删除被拒后教室仍在列表中').toBeVisible()

    // 接口交叉复核：确实被业务拒绝（code 400），且教室未被删除
    const rejected = await deleteClassroomApi(page.request, token, 1)
    expect(rejected.body && rejected.body.code, `删除 A101 应被拒绝：${rejected.text.slice(0, 120)}`).toBe(400)
    const list = expectOk(await listClassroomsManage(page.request, token, { page: 1, size: 50, keyword: 'A101' }), '查 A101')
    expect(list.records.some((r) => r.id === 1), 'A101 不应被删除').toBe(true)
  })
})