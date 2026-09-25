/**
 * 页面级定位/交互辅助（新增文件；不改动既有 e2e/helpers/*）
 *
 * 统一 Element Plus 交互写法，避免各 spec 各写一套 nth-child 长链：
 *   - 表单项按中文 label 精确匹配（:text-is），而不是依赖顺序
 *   - 下拉选项按 role=option + 精确文案选择（EP 选项用 v-show 隐藏，role 选择器天然排除隐藏项）
 *   - 日期范围用输入框直填 + Enter，再用 Esc 关闭浮层，避免浮层遮挡后续按钮点击
 */
import { expect } from '@playwright/test'

/**
 * 表单一条 el-form-item（按 label 文案精确匹配）
 * 必须用 CSS :has() 而不是 filter({ has })：filter 的 has 定位器会以「外层元素」为根再解析，
 * 传入 scope 前缀（如 .search-card）将永远匹配不到，故此处用相对选择器组合。
 */
export function formItem(scope, label) {
  return scope.locator(`.el-form-item:has(.el-form-item__label:text-is("${label}"))`)
}

/** 搜索卡片（.search-card）里的表单项 */
export function searchItem(page, label) {
  return formItem(page.locator('.search-card'), label)
}

/** 表格数据行（el-table__row 只在 tbody 出现，表头是 el-table__header-row） */
export function rowOf(page, text) {
  return page.locator('tr.el-table__row').filter({ hasText: text })
}

/** 打开 el-select 并选中精确文案的选项 */
export async function pickOption(page, scope, label) {
  await scope.locator('.el-select').first().click()
  await page.getByRole('option', { name: label, exact: true }).first().click()
}

/** 填写 daterange（开始日期/结束日期）并回车提交，Esc 收起浮层 */
export async function fillDateRange(page, start, end) {
  const startInput = page.getByPlaceholder('开始日期')
  const endInput = page.getByPlaceholder('结束日期')
  await startInput.click()
  await startInput.fill(start)
  await endInput.fill(end)
  await page.keyboard.press('Enter')
  await page.keyboard.press('Escape')
  await expect(startInput).toHaveValue(start)
  await expect(endInput).toHaveValue(end)
}

/** 切换分页每页条数（Element Plus 分页 sizes 下拉） */
export async function setPageSize(page, label) {
  await page.locator('.el-pagination .el-select').first().click()
  await page.getByRole('option', { name: label, exact: true }).first().click()
}

/** 断言某条 ElMessage 提示出现（EP 全局消息，3 秒自动消失） */
export async function expectMessage(page, text) {
  await expect(page.locator('.el-message').filter({ hasText: text }).first()).toBeVisible()
}

/** 读取分页“共 N 条”里的数字 */
export async function readTotal(page) {
  const text = await page.locator('.el-pagination__total').innerText()
  return Number(text.replace(/[^\d]/g, ''))
}