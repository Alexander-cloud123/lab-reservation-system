/**
 * URL 深链：筛选、分页、展开状态与看板/日历视图可分享、刷新及前进/后退恢复。
 * 真实后端验证请求参数；字典完整性用独立分页 mock 验证，不依赖种子页数。
 */
import { test, expect } from '@playwright/test'
import dayjs from 'dayjs'
import { loginAs } from '../helpers/auth'
import { futureDate } from '../helpers/data'
import { mockAiOff } from '../helpers/ai'

function searchParams(page) {
  return new URL(page.url()).searchParams
}

async function openAndWait(page, who, path, apiPath, predicate = () => true) {
  await loginAs(page, { who, aiEnabled: false })
  const [response] = await Promise.all([
    page.waitForResponse((r) => {
      const url = new URL(r.url())
      return r.request().method() === 'GET' && url.pathname === apiPath && predicate(url.searchParams)
    }),
    page.goto(path)
  ])
  expect(response.status()).toBe(200)
  return response
}

const deepLinkCases = [
  {
    name: '教室列表', who: 'student', path: '/student/home',
    query: 'size=6&q=A&building=信息楼&type=1&date=2027-01-05&expanded=1',
    api: '/api/classroom/list',
    expected: { size: '6', keyword: 'A', building: '信息楼', type: '1', date: '2027-01-05', page: '1' },
    ready: '.card-grid', predicate: (p) => p.get('size') !== '500'
  },
  {
    name: '我的预约', who: 'student', path: '/student/my-reservations',
    query: 'size=20&status=1', api: '/api/reservation/mine',
    expected: { size: '20', status: '1', page: '1' }, ready: '.reserve-card'
  },
  {
    name: '用户管理', who: 'admin', path: '/admin/users',
    query: 'size=20&q=zhang&role=0&status=1&expanded=1', api: '/api/user/manage',
    expected: { size: '20', keyword: 'zhang', role: '0', status: '1', page: '1' }, ready: '.el-table'
  },
  {
    name: '教室资源管理', who: 'admin', path: '/admin/classrooms',
    query: 'size=20&building=信息楼&type=1&status=1&expanded=1', api: '/api/classroom/manage',
    expected: { size: '20', building: '信息楼', type: '1', status: '1', page: '1' },
    ready: '.el-table', predicate: (p) => p.get('size') !== '500'
  },
  {
    name: '预约审核', who: 'admin', path: '/admin/audits',
    query: 'size=20&q=E2E&status=0&start=2027-01-01&end=2027-01-31&expanded=1', api: '/api/reservation/manage',
    expected: { size: '20', keyword: 'E2E', status: '0', startDate: '2027-01-01', endDate: '2027-01-31', page: '1' },
    ready: '.el-table'
  },
  {
    name: '预约记录', who: 'admin', path: '/admin/records',
    query: 'size=20&classroomId=1&status=1&start=2027-01-01&end=2027-01-31&expanded=1', api: '/api/reservation/manage',
    expected: { size: '20', classroomId: '1', status: '1', startDate: '2027-01-01', endDate: '2027-01-31', page: '1' },
    ready: '.el-table'
  }
]

for (const item of deepLinkCases) {
  test(`${item.name}：深链与刷新恢复筛选并传给后端`, async ({ page }) => {
    await mockAiOff(page)
    const path = `${item.path}?${item.query}`
    const response = await openAndWait(page, item.who, path, item.api, item.predicate)
    const requestQuery = new URL(response.url()).searchParams
    for (const [key, value] of Object.entries(item.expected)) {
      expect(requestQuery.get(key), `${item.name} 请求参数 ${key}`).toBe(value)
    }
    await expect(page.locator(item.ready).first()).toBeVisible()
    await page.reload()
    await expect(page.locator(item.ready).first()).toBeVisible()
    const current = searchParams(page)
    for (const key of item.query.split('&').map((part) => part.split('=')[0])) {
      expect(current.has(key), `刷新后保留 ${key}`).toBe(true)
    }
    if (item.path !== '/student/my-reservations') {
      const urlBeforeRefresh = page.url()
      const refreshP = page.waitForResponse((response) => {
        const url = new URL(response.url())
        return response.request().method() === 'GET' && url.pathname === item.api
          && (item.predicate ? item.predicate(url.searchParams) : true)
      })
      await page.getByRole('button', { name: '查询', exact: true }).click()
      await refreshP
      expect(page.url(), '相同条件查询仍刷新，且不重复写历史').toBe(urlBeforeRefresh)
    }
  })
}

const keywordHistoryCases = [
  { name: '教室列表', who: 'student', path: '/student/home', api: '/api/classroom/list', placeholder: '教室名称 / 编号' },
  { name: '用户管理', who: 'admin', path: '/admin/users', api: '/api/user/manage', placeholder: '账号 / 姓名 / 学号' },
  { name: '教室资源管理', who: 'admin', path: '/admin/classrooms', api: '/api/classroom/manage', placeholder: '教室名称 / 编号' },
  { name: '预约审核', who: 'admin', path: '/admin/audits', api: '/api/reservation/manage', placeholder: '用户账号 / 姓名 / 教室名称' },
  { name: '预约记录', who: 'admin', path: '/admin/records', api: '/api/reservation/manage', placeholder: '账号 / 姓名' }
]

for (const item of keywordHistoryCases) {
  test(`${item.name}：连续查询后浏览器后退恢复上一状态`, async ({ page }) => {
    await mockAiOff(page)
    await openAndWait(page, item.who, item.path, item.api, (p) => p.get('size') !== '500')
    const input = page.getByPlaceholder(item.placeholder)
    await input.fill('A101')
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await expect.poll(() => searchParams(page).get('q')).toBe('A101')
    await input.fill('B101')
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await expect.poll(() => searchParams(page).get('q')).toBe('B101')
    await page.goBack()
    await expect.poll(() => searchParams(page).get('q')).toBe('A101')
    await expect(input).toHaveValue('A101')
  })
}

test('我的预约：状态 tab 与分页可通过历史恢复', async ({ page }) => {
  await mockAiOff(page)
  await openAndWait(page, 'student', '/student/my-reservations', '/api/reservation/mine')
  await page.getByRole('tab', { name: '已通过' }).click()
  await expect.poll(() => searchParams(page).get('status')).toBe('1')
  await page.getByRole('tab', { name: '已驳回' }).click()
  await expect.poll(() => searchParams(page).get('status')).toBe('2')
  await page.goBack()
  await expect.poll(() => searchParams(page).get('status')).toBe('1')
  await expect(page.getByRole('tab', { name: '已通过' })).toHaveAttribute('aria-selected', 'true')
  const urlBeforeRefresh = page.url()
  const refreshP = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/reservation/mine' && url.searchParams.get('status') === '1'
  })
  await page.getByRole('tab', { name: '已通过' }).click()
  await refreshP
  expect(page.url(), '当前状态 tab 可重复刷新且不写历史').toBe(urlBeforeRefresh)
})

test('非法 query 被规范化，展开状态不触发列表请求', async ({ page }) => {
  await mockAiOff(page)
  await openAndWait(
    page,
    'admin',
    '/admin/users?page=0&size=999&role=9&status=x&bogus=1',
    '/api/user/manage'
  )
  await expect(page.locator('.el-table')).toBeVisible()
  await expect.poll(() => searchParams(page).toString()).toBe('')
  let listRequests = 0
  const onRequest = (request) => {
    if (new URL(request.url()).pathname === '/api/user/manage') listRequests += 1
  }
  page.on('request', onRequest)
  await page.getByRole('button', { name: '更多筛选' }).click()
  await expect.poll(() => searchParams(page).get('expanded')).toBe('1')
  await page.waitForTimeout(250)
  expect(listRequests, '仅展开筛选不应重新请求').toBe(0)
  page.off('request', onRequest)
})

test('教室列表：本地筛选存储不可用时不阻断查询与重置', async ({ page }) => {
  await page.addInitScript(() => {
    for (const method of ['setItem', 'removeItem']) {
      const original = Storage.prototype[method]
      Storage.prototype[method] = function (key, ...args) {
        if (String(key).startsWith('reservation_filter_')) {
          throw new DOMException('Storage disabled by test', 'SecurityError')
        }
        return original.call(this, key, ...args)
      }
    }
  })
  await mockAiOff(page)
  await openAndWait(page, 'student', '/student/home', '/api/classroom/list', (p) => p.get('size') !== '500')
  await page.getByPlaceholder('教室名称 / 编号').fill('A101')
  const searchP = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/classroom/list' && url.searchParams.get('keyword') === 'A101'
  })
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await searchP
  await expect.poll(() => searchParams(page).get('q')).toBe('A101')
  const resetP = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/classroom/list' && url.searchParams.get('size') !== '500' && !url.searchParams.has('keyword')
  })
  await page.getByRole('button', { name: '重置', exact: true }).click()
  await resetP
  await expect.poll(() => searchParams(page).get('q')).toBe(null)
})

test('预约日历：空标量与额外参数会被规范化', async ({ page }) => {
  await mockAiOff(page)
  await openAndWait(page, 'student', '/student/calendar?view=&date=&classroomId=&bogus=1', '/api/reservation/calendar')
  await expect(page.locator('.calendar-container')).toBeVisible()
  await expect.poll(() => searchParams(page).toString()).toBe('')
})

test('教室列表：高页码切页长写回 page=1，后退恢复页码', async ({ page }) => {
  await mockAiOff(page)
  await openAndWait(page, 'student', '/student/home?size=6', '/api/classroom/list', (p) => p.get('size') !== '500')
  await page.locator('.el-pagination .btn-next').click()
  await expect.poll(() => searchParams(page).get('page')).toBe('2')
  await page.locator('.el-pagination .el-select').click()
  await page.locator('.el-select-dropdown:visible').getByText(/12\s*条\/页/).click()
  await expect.poll(() => searchParams(page).get('size')).toBe(null)
  await expect.poll(() => searchParams(page).get('page')).toBe(null)
  await page.goBack()
  await expect.poll(() => searchParams(page).get('page')).toBe('2')
  await expect.poll(() => searchParams(page).get('size')).toBe('6')
})

test('完整楼栋字典：学生与管理端都能获取第二页字典中的楼栋', async ({ page }) => {
  await mockAiOff(page)
  const room = (id, building) => ({
    id, name: `${building}教室`, roomNo: `X${id}`, building, type: 1, capacity: 60,
    equipment: '', description: '', status: 1
  })
  const mockDictionary = async (route) => {
    const url = new URL(route.request().url())
    if (url.searchParams.get('size') !== '500') return route.continue()
    const pageNo = Number(url.searchParams.get('page') || '1')
    const records = pageNo === 1
      ? Array.from({ length: 500 }, (_, index) => room(index + 1, '信息楼'))
      : [room(501, '未来楼')]
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 200, message: 'ok', data: { records, total: 501, page: pageNo, size: 500 } })
    })
  }
  await page.route('**/api/classroom/manage**', mockDictionary)
  await page.route('**/api/classroom/list**', mockDictionary)
  const cases = [
    { who: 'admin', path: '/admin/classrooms?expanded=1', api: '/api/classroom/manage' },
    { who: 'student', path: '/student/home?expanded=1', api: '/api/classroom/list' }
  ]
  for (const item of cases) {
    await openAndWait(page, item.who, item.path, item.api, (p) => p.get('size') !== '500')
    const buildingSelect = page.locator('.el-form-item').filter({ hasText: '楼栋' }).locator('.el-select')
    await buildingSelect.click()
    await expect(page.locator('.el-select-dropdown:visible').getByText('未来楼', { exact: true })).toBeVisible()
  }
})

test('预约记录：深链恢复后导出继续使用相同筛选', async ({ page }) => {
  await mockAiOff(page)
  await openAndWait(
    page,
    'admin',
    '/admin/records?classroomId=1&status=1&start=2027-01-01&end=2027-01-31',
    '/api/reservation/manage'
  )
  const [request] = await Promise.all([
    page.waitForRequest((r) => new URL(r.url()).pathname === '/api/reservation/export'),
    page.getByRole('button', { name: '导出 Excel' }).click()
  ])
  const query = new URL(request.url()).searchParams
  expect(query.get('classroomId')).toBe('1')
  expect(query.get('status')).toBe('1')
  expect(query.get('startDate')).toBe('2027-01-01')
  expect(query.get('endDate')).toBe('2027-01-31')
})

test('数据看板：快捷区间深链、历史恢复与非法区间规范化', async ({ page }) => {
  await mockAiOff(page)
  await openAndWait(page, 'admin', '/admin/dashboard?range=7', '/api/stats/usage-rate')
  await expect.poll(() => searchParams(page).get('range')).toBe('7')
  await page.locator('.el-radio-button').filter({ hasText: '近 90 天' }).click()
  await expect.poll(() => searchParams(page).get('range')).toBe('90')
  await page.goBack()
  await expect.poll(() => searchParams(page).get('range')).toBe('7')
  await page.goto('/admin/dashboard?range=bad&start=2027-02-02&end=2027-01-01')
  await expect.poll(() => searchParams(page).toString()).toBe('')
})

test('预约日历：视图、日期与教室深链及历史恢复', async ({ page }) => {
  await mockAiOff(page)
  const date = futureDate(1)
  await openAndWait(
    page,
    'student',
    `/student/calendar?view=week&date=${date}&classroomId=1`,
    '/api/reservation/calendar',
    (p) => p.get('classroomId') === '1'
  )
  await expect(page.getByRole('radio', { name: '周视图' })).toBeChecked()
  await expect(page.locator('.fc-daygrid-day')).toHaveCount(7)
  await expect.poll(() => searchParams(page).get('date')).toBe(date)
  await page.locator('.el-radio-button').filter({ hasText: '月视图' }).click()
  await expect.poll(() => searchParams(page).get('view')).toBe(null)
  await page.goBack()
  await expect.poll(() => searchParams(page).get('view')).toBe('week')
  await expect(page.getByRole('radio', { name: '周视图' })).toBeChecked()
  await page.locator('.fc-next-button').click()
  await expect.poll(() => searchParams(page).get('date')).not.toBe(date)
  await page.goBack()
  await expect.poll(() => searchParams(page).get('date')).toBe(date)
  await expect(page.getByRole('radio', { name: '周视图' })).toBeChecked()
})

test('看板自定义日期区间写入 start/end 并恢复', async ({ page }) => {
  await mockAiOff(page)
  await openAndWait(page, 'admin', '/admin/dashboard', '/api/stats/usage-rate')
  const start = dayjs().subtract(10, 'day').format('YYYY-MM-DD')
  const end = dayjs().subtract(3, 'day').format('YYYY-MM-DD')
  await page.getByPlaceholder('开始日期').fill(start)
  await page.getByPlaceholder('结束日期').fill(end)
  await page.keyboard.press('Enter')
  await page.keyboard.press('Escape')
  await expect.poll(() => searchParams(page).get('start')).toBe(start)
  await expect.poll(() => searchParams(page).get('end')).toBe(end)
  await expect.poll(() => searchParams(page).get('range')).toBe(null)
  await page.reload()
  await expect(page.getByPlaceholder('开始日期')).toHaveValue(start)
  await expect(page.getByPlaceholder('结束日期')).toHaveValue(end)
})
