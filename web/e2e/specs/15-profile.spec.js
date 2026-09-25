/**
 * 学生端 - 个人中心（/student/profile，Profile.vue）
 *
 * 覆盖：
 *   1. 身份卡（姓名/角色/学号/账号）与四项数据概览 ↔ GET /api/user/stats 一致
 *   2. 快捷功能四格：我的预约 / 预约日历 / 教室列表 跳转 + 常用教室页内滚动
 *   3. 常用教室：收藏卡片字段渲染 + 空态引导（拦截接口返回空页）
 *   4. 消息通知：由预约状态动态生成（待审核 / 已驳回含审核备注）、单条已读、全部已读
 *   5. 编辑资料：预填 → 前端校验（姓名必填 / 手机号格式）→ 保存后页面同步 → 复原原值
 *   6. 修改密码：前端校验 → 原密码错误 → 改密成功跳登录页、新密码可登录（用临时账号，不动种子账号）
 *
 * 数据隔离：学生端页面统一用教室 A101(1)/A102(2)/A201(3) 与 futureDate(4)~(6)
 *   （futureDate(1)~(3) 已分配给 13-my-reservation，避免抢时段）；
 *   造数一律「先冲突检测挑空档再提交」。
 *
 * ⚠️ 跑完必须清理（ASCII 前缀，中文 LIKE 在 docker exec 管道会失效）：
 *   docker exec tmp-res-mysql mysql -uroot -proot -e "use reservation; delete from reservation where purpose like 'E2E%';"
 *   docker exec tmp-res-mysql mysql -uroot -proot -e "use reservation; delete from user_favorite where user_id in (select id from sys_user where username like 'e2e%'); delete from reservation where user_id in (select id from sys_user where username like 'e2e%'); delete from sys_user where username like 'e2e%';"
 */
import { test, expect } from '@playwright/test'
import dayjs from 'dayjs'
import { API_BASE, loginAs, apiLogin } from '../helpers/auth'
import { expectOk, createReservation, auditReservation, toggleFavorite, listFavorites, getClassroom, tryCancel } from '../helpers/api'
import { pageToken, isSlotFree } from '../helpers/stable-seed'
import { expectMessage } from '../helpers/ui'
import { CLASSROOMS, TYPE_LABELS, e2ePurpose, futureDate, SLOTS } from '../helpers/data'

const PATH = '/student/profile'

/** 每次运行唯一后缀：终态记录（已驳回）无法删除，避免与历史遗留数据同文案导致严格模式多匹配 */
const RUN = String(Date.now()).slice(-6)
const tag = (t) => e2ePurpose(`${t}-${RUN}`)

/** 本页可用资源（见文件头隔离说明） */
const ROOMS = [CLASSROOMS.A101.id, CLASSROOMS.A102.id, CLASSROOMS.A201.id]
const DATES = [futureDate(4), futureDate(5), futureDate(6)]
const SLOTS_LIST = [SLOTS.morning, SLOTS.noon, SLOTS.afternoon]

/** 底层请求（本 spec 私有：仅用于取当前用户信息/统计、注册与登录临时账号） */
async function raw(request, token, method, path, data) {
  const resp = await request.fetch(API_BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    data
  })
  const text = await resp.text()
  let body = null
  try {
    body = JSON.parse(text)
  } catch {
    // 非 JSON 响应保留 text
  }
  return { status: resp.status(), body, text }
}

async function openProfile(page) {
  await loginAs(page, { who: 'student', path: PATH, aiEnabled: false })
  await expect(page.locator('.identity-card')).toBeVisible()
}

/** 在候选 (教室 × 日期 × 时段) 中挑第一个无冲突时段 */
async function freeSlot(request, token) {
  for (const classroomId of ROOMS) {
    for (const date of DATES) {
      for (const [startTime, endTime] of SLOTS_LIST) {
        if (await isSlotFree(request, token, { classroomId, date, startTime, endTime })) {
          return { classroomId, date, startTime, endTime }
        }
      }
    }
  }
  throw new Error('候选时段全部被占用，无法造数')
}

/** 造一条「待审核」预约，返回 id */
async function seedPending(request, token, slot, purpose) {
  const res = await createReservation(request, token, {
    classroomId: slot.classroomId,
    reserveDate: slot.date,
    startTime: slot.startTime,
    endTime: slot.endTime,
    purpose
  })
  return expectOk(res, `造待审核预约（教室 ${slot.classroomId} ${slot.date} ${slot.startTime}-${slot.endTime}）`)
}

/** 注入指定账号的登录态（loginAs 只支持预设别名，临时账号需自建；登录接口 role 必填）
 *  注意：这里用 goto + evaluate 一次性写 localStorage，而不是 addInitScript——
 *  addInitScript 会在后续每次文档加载前重新注入 Token，会把「改密后登出跳登录页」重新顶回首页，
 *  使"改密即下线"链路无法验证。 */
async function loginAsAccount(page, { username, password, role = 0, path }) {
  const data = expectOk(await raw(page.request, null, 'POST', '/api/user/login', { username, password, role }), `登录 ${username}`)
  await page.goto('/login')
  await page.evaluate(
    ([token, user]) => {
      localStorage.setItem('reservation_token', token)
      localStorage.setItem('reservation_user', JSON.stringify(user))
      localStorage.setItem('reservation_ai_enabled', 'false')
    },
    [data.token, data.user]
  )
  if (path) {
    await page.goto(path)
  }
  return data
}

/** 修改密码弹窗 */
const pwdDialog = (page) => page.locator('.el-dialog:has(.el-dialog__title:text-is("修改密码"))')
/** 编辑资料弹窗 */
const infoDialog = (page) => page.locator('.el-dialog:has(.el-dialog__title:text-is("编辑资料"))')
/** 设置列表里的一行 */
const settingsRow = (page, label) => page.locator('.settings-row').filter({ hasText: label })

test.describe('个人中心', () => {
  test('身份卡与数据概览：展示字段与统计接口一致', async ({ page, request }) => {
    await openProfile(page)
    const token = await pageToken(page)

    const info = expectOk(await raw(request, token, 'GET', '/api/user/info'), '取当前用户信息')
    const stats = expectOk(await raw(request, token, 'GET', '/api/user/stats'), '取个人统计')

    // 身份卡
    await expect(page.locator('.identity-name')).toHaveText(info.name)
    await expect(page.locator('.identity-role')).toHaveText('学生')
    await expect(page.locator('.identity-meta')).toContainText(`学号 ${info.studentNo}`)
    await expect(page.locator('.identity-meta')).toContainText(`账号 ${info.username}`)
    await expect(page.locator('.page-title')).toContainText(info.name)
    // 头像占位取姓名末两位
    await expect(page.locator('.identity-avatar')).toHaveText(info.name.slice(-2))

    // 数据概览：四项，值与接口一致
    await expect(page.locator('.overview-item')).toHaveCount(4)
    const expectItems = [
      ['累计预约', String(stats.totalReservations ?? 0)],
      ['本月预约', String(stats.monthReservations ?? 0)],
      ['审核通过率', `${stats.approvalRate ?? 0}%`],
      ['最近一次预约', stats.lastReservationTime ? dayjs(stats.lastReservationTime).format('YYYY-MM-DD HH:mm') : '暂无']
    ]
    for (const [label, value] of expectItems) {
      const item = page.locator('.overview-item').filter({ hasText: label })
      await expect(item.locator('.overview-label')).toHaveText(label)
      await expect(item.locator('.overview-value'), `概览项「${label}」`).toHaveText(value)
    }
  })

  test('快捷功能四格：三项跳转 + 常用教室页内滚动定位', async ({ page }) => {
    await openProfile(page)
    const quick = (name) => page.locator('.quick-item').filter({ hasText: name })
    await expect(page.locator('.quick-item')).toHaveCount(4)

    for (const [name, url] of [
      ['我的预约', /\/student\/my-reservations$/],
      ['预约日历', /\/student\/calendar$/],
      ['教室列表', /\/student\/home$/]
    ]) {
      await quick(name).click()
      await expect(page, `点击「${name}」`).toHaveURL(url)
      await page.goBack()
      await expect(page.locator('.quick-grid')).toBeVisible()
    }

    // 常用教室：不跳页，滚动到收藏区
    await quick('常用教室').click()
    await expect(page.locator('#favorite-section')).toBeInViewport()
    await expect(page).toHaveURL(/\/student\/profile$/)
  })

  test('常用教室：收藏卡片字段完整，空态给出引导', async ({ page, request }) => {
    await openProfile(page)
    const token = await pageToken(page)

    const target = CLASSROOMS.A201
    // 教室展示字段（名称/楼栋/房号/类型）以接口为准，不硬编码（data.js 里只是编号索引）
    const room = expectOk(await getClassroom(request, token, target.id), '取教室详情')
    const before = expectOk(await listFavorites(request, token), '取收藏列表')
    const hadIt = before.some((f) => Number(f.classroomId) === target.id)
    if (!hadIt) {
      expectOk(await toggleFavorite(request, token, target.id), '收藏教室')
    }
    await page.reload()

    const item = page.locator('.fav-item').filter({ hasText: room.name })
    await expect(item).toBeVisible()
    await expect(item.locator('.fav-name')).toHaveText(room.name)
    await expect(item.locator('.fav-meta')).toContainText(`${room.building}-${room.roomNo}`)
    await expect(item.locator('.fav-meta')).toContainText(TYPE_LABELS[room.type])
    await expect(item.locator('.fav-meta')).toContainText(`${room.capacity}人`)

    // 空态：拦截收藏列表返回空数组（按 pathname 判定，避免误拦 /src/api/*.js 源码模块）
    const emptyMatcher = (url) => url.pathname === '/api/favorite/list'
    const emptyHandler = (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: '操作成功', data: [] })
      })
    await page.route(emptyMatcher, emptyHandler)
    await page.reload()
    const section = page.locator('#favorite-section')
    await expect(section.locator('.el-empty__description')).toContainText('还没有收藏的教室')
    await expect(section.getByRole('button', { name: '去收藏教室' })).toBeVisible()
    await page.unroute(emptyMatcher, emptyHandler)

    // 复原：本用例前未收藏则取消收藏
    if (!hadIt) {
      expectOk(await toggleFavorite(request, token, target.id), '取消收藏（复原）')
    }
  })

  test('消息通知：由预约状态动态生成，单条已读与全部已读生效', async ({ page, request }) => {
    await openProfile(page)
    const token = await pageToken(page)
    const admin = await apiLogin(request, 'admin')

    // 造一条待审核（→ 预约待审核）与一条已驳回（→ 审核驳回，含审核备注）
    const pendingSlot = await freeSlot(request, token)
    const pendingId = await seedPending(request, token, pendingSlot, tag('15-待审核'))

    const rejectSlot = await freeSlot(request, token)
    const rejectId = await seedPending(request, token, rejectSlot, tag('15-驳回'))
    const remark = `E2E审核备注-${RUN}`
    expectOk(await auditReservation(request, admin.token, rejectId, 2, remark), '驳回预约')

    await page.reload()

    // 待审核消息：标题/正文按预约状态生成
    // 用 .first() 兜底：待审核不参与冲突判定，历史遗留的同教室同时段「待审核」会在列表里重复出现
    // （消息按时间倒序，本次新造的排在最前），避免严格模式多匹配。
    const stamp = `${pendingSlot.date} ${pendingSlot.startTime}-${pendingSlot.endTime}`
    const pendingMsg = page.locator('.msg-item').filter({ hasText: '预约待审核' }).filter({ hasText: stamp }).first()
    await expect(pendingMsg).toBeVisible()
    await expect(pendingMsg.locator('.msg-title')).toHaveText('预约待审核')
    await expect(pendingMsg.locator('.msg-content')).toContainText('已提交，等待管理员审核')
    await expect(pendingMsg).toHaveClass(/unread/)
    await expect(pendingMsg.locator('.msg-dot')).toBeVisible()

    // 驳回消息：正文带审核备注
    const rejectStamp = `${rejectSlot.date} ${rejectSlot.startTime}-${rejectSlot.endTime}`
    const rejectMsg = page.locator('.msg-item').filter({ hasText: '审核驳回' }).filter({ hasText: rejectStamp })
    await expect(rejectMsg).toBeVisible()
    await expect(rejectMsg.locator('.msg-content')).toContainText(`被驳回：${remark}`)
    await expect(rejectMsg.locator('.msg-dot')).toBeVisible()

    // 未读计数标签存在
    await expect(page.locator('.section-actions .el-tag')).toContainText('条未读')

    // 单条标记已读：已读状态写入 localStorage（key 带用户维度），刷新后保持
    // 已知缺陷（登记为阶段3前端修复项）：markRead 只写 localStorage、未触发响应式更新，
    // 点击后当前页面不会立即重渲染未读标记，故此处断言「持久化效果」而非即时视觉
    await pendingMsg.click()
    await page.reload()
    const pendingRead = page.locator('.msg-item').filter({ hasText: '预约待审核' }).filter({ hasText: stamp }).first()
    await expect(pendingRead).not.toHaveClass(/unread/)
    await expect(pendingRead.locator('.msg-dot')).toHaveCount(0)

    // 全部标为已读：落盘后刷新，未读标记与计数标签全部消失
    await page.getByRole('button', { name: '全部标为已读' }).click()
    await page.reload()
    await expect(page.locator('.msg-dot')).toHaveCount(0)
    await expect(page.locator('.section-actions .el-tag')).toHaveCount(0)

    // 收尾：待审核可取消；已驳回为终态（统一由 SQL 清理）
    await tryCancel(request, token, pendingId)
  })

  test('编辑资料：预填、前端校验、保存同步并复原', async ({ page, request }) => {
    await openProfile(page)
    const token = await pageToken(page)
    const info = expectOk(await raw(request, token, 'GET', '/api/user/info'), '取当前用户信息')

    await page.locator('.identity-edit').click()
    const dlg = infoDialog(page)
    await expect(dlg).toBeVisible()
    // 预填当前资料
    await expect(dlg.getByPlaceholder('请输入真实姓名')).toHaveValue(info.name || '')
    await expect(dlg.getByPlaceholder('请输入邮箱（选填）')).toHaveValue(info.email || '')
    await expect(dlg.getByPlaceholder('请输入 11 位手机号（选填）')).toHaveValue(info.phone || '')

    // 校验一：姓名必填
    await dlg.getByPlaceholder('请输入真实姓名').fill('')
    await dlg.getByRole('button', { name: '保存' }).click()
    await expect(dlg.locator('.el-form-item:has(.el-form-item__label:text-is("姓名")) .el-form-item__error')).toContainText('请输入姓名')

    // 校验二：手机号格式（选填，但填写须为 11 位）
    await dlg.getByPlaceholder('请输入 11 位手机号（选填）').fill('123')
    await dlg.getByPlaceholder('请输入真实姓名').fill(info.name)
    await expect(dlg.locator('.el-form-item:has(.el-form-item__label:text-is("手机号")) .el-form-item__error')).toContainText('手机号格式不正确')

    // 保存有效数据 → 页面同步更新
    const newEmail = `e2e_profile_${RUN}@test.com`
    await dlg.getByPlaceholder('请输入邮箱（选填）').fill(newEmail)
    await dlg.getByPlaceholder('请输入 11 位手机号（选填）').fill('13800138000')
    await dlg.getByRole('button', { name: '保存' }).click()
    await expectMessage(page, '个人信息修改成功')
    await expect(dlg).toBeHidden()
    await expect(settingsRow(page, '邮箱').locator('.settings-value')).toHaveText(newEmail)
    await expect(settingsRow(page, '手机号').locator('.settings-value')).toHaveText('13800138000')

    // 复原为原始资料（保持库与页面基线）
    await page.locator('.identity-edit').click()
    await expect(infoDialog(page)).toBeVisible()
    await infoDialog(page).getByPlaceholder('请输入邮箱（选填）').fill(info.email)
    await infoDialog(page).getByPlaceholder('请输入 11 位手机号（选填）').fill(info.phone)
    await infoDialog(page).getByRole('button', { name: '保存' }).click()
    await expectMessage(page, '个人信息修改成功')
    await expect(settingsRow(page, '邮箱').locator('.settings-value')).toHaveText(info.email)
  })

  test('修改密码：前端校验与原密码错误拦截', async ({ page }) => {
    await openProfile(page)

    await settingsRow(page, '修改密码').click()
    const dlg = pwdDialog(page)
    await expect(dlg).toBeVisible()

    // 必填校验
    await dlg.getByRole('button', { name: '修改密码' }).click()
    await expect(dlg.locator('.el-form-item:has(.el-form-item__label:text-is("原密码")) .el-form-item__error')).toContainText('请输入原密码')
    await expect(dlg.locator('.el-form-item:has(.el-form-item__label:text-is("新密码")) .el-form-item__error')).toContainText('请输入新密码')
    await expect(dlg.locator('.el-form-item:has(.el-form-item__label:text-is("确认密码")) .el-form-item__error')).toContainText('请再次输入新密码')

    // 新密码长度下限（规则 trigger 为 blur，填写后需失焦才触发校验）
    await dlg.getByPlaceholder('请输入原密码').fill('123456')
    await dlg.getByPlaceholder('不少于 6 位').fill('123')
    await dlg.getByPlaceholder('不少于 6 位').blur()
    await expect(dlg.locator('.el-form-item:has(.el-form-item__label:text-is("新密码")) .el-form-item__error')).toContainText('新密码长度不能少于 6 位')

    // 两次新密码一致
    await dlg.getByPlaceholder('不少于 6 位').fill('654321')
    await dlg.getByPlaceholder('不少于 6 位').blur()
    await dlg.getByPlaceholder('再次输入新密码').fill('654322')
    await dlg.getByPlaceholder('再次输入新密码').blur()
    await expect(dlg.locator('.el-form-item:has(.el-form-item__label:text-is("确认密码")) .el-form-item__error')).toContainText('两次输入的新密码不一致')

    // 原密码错误：走二次确认后被后端拦截，页面停留在个人中心
    await dlg.getByPlaceholder('请输入原密码').fill('e2e_wrong_pwd')
    await dlg.getByPlaceholder('请输入原密码').blur()
    await dlg.getByPlaceholder('再次输入新密码').fill('654321')
    await dlg.getByPlaceholder('再次输入新密码').blur()
    await dlg.getByRole('button', { name: '修改密码' }).click()
    const box = page.locator('.el-message-box')
    await expect(box).toBeVisible()
    await expect(box).toContainText('修改密码确认')
    await expect(box).toContainText('需要重新登录')
    await box.getByRole('button', { name: '确定修改' }).click()
    await expectMessage(page, '原密码错误')
    await expect(page).toHaveURL(/\/student\/profile$/)
  })

  test('修改密码成功：跳转登录页且新密码可登录（临时账号）', async ({ page, request }) => {
    // 不动种子账号密码：注册一个临时账号完成改密闭环（跑完由 SQL 清理 e2e% 账号）
    const username = `e2e_profile_${RUN}`
    const oldPwd = '123456'
    const newPwd = 'e2eNew654321'
    expectOk(
      await raw(request, null, 'POST', '/api/user/register', {
        username,
        password: oldPwd,
        confirmPassword: oldPwd,
        name: 'E2E改密学生',
        studentNo: `E2E${RUN}`
      }),
      '注册临时账号'
    )
    await loginAsAccount(page, { username, password: oldPwd, path: PATH })
    await expect(page.locator('.identity-card')).toBeVisible()

    await settingsRow(page, '修改密码').click()
    const dlg = pwdDialog(page)
    await expect(dlg).toBeVisible()
    await dlg.getByPlaceholder('请输入原密码').fill(oldPwd)
    await dlg.getByPlaceholder('不少于 6 位').fill(newPwd)
    await dlg.getByPlaceholder('再次输入新密码').fill(newPwd)
    await dlg.getByRole('button', { name: '修改密码' }).click()

    const box = page.locator('.el-message-box')
    await expect(box).toBeVisible()
    await box.getByRole('button', { name: '确定修改' }).click()

    // 改密即下线：清登录态并跳登录页
    // （登出后的跳转会让全局 ElMessage 随文档卸载而消失，故此处以 URL + 「新密码可登录」为判据）
    await expect(page).toHaveURL(/\/login/)

    // 新密码可登录、旧密码失效
    expectOk(await raw(request, null, 'POST', '/api/user/login', { username, password: newPwd, role: 0 }), '新密码登录')
    const oldLogin = await raw(request, null, 'POST', '/api/user/login', { username, password: oldPwd, role: 0 })
    expect(oldLogin.body && oldLogin.body.code, '旧密码应登录失败').not.toBe(200)
  })
})