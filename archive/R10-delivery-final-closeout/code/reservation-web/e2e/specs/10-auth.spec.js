/**
 * 登录 / 注册 E2E（浏览器级）
 *
 * 覆盖（对应任务清单 A 的 1~9）：
 *   1) 空表单提交 → 必填校验、不跳转
 *   2) 角色单选切换后正常提交
 *   3) 错误凭证（不存在账号 / 已存在账号密码错误）→ 错误提示且停留登录页
 *   4) 正确登录（学生 / 管理员）→ 各自角色首页
 *   5) 已登录访问 /login → 被守卫跳回本角色首页
 *   6) 注册：两次密码不一致 → 校验提示
 *   7) 注册：账号唯一性（用 'admin' 触发）→ 被拒且有提示
 *   8) 注册成功 → 新账号可登录成功
 *   9) 登录后「当日即将开始的预约」→ 顶部温和提醒（非阻塞）
 *
 * 约束遵守：
 *   - 不改种子账号（不登录失败超过 1 次；已存在账号的失败用例在末尾用一次成功登录复位失败计数）
 *   - 新注册用户名一律 'e2e_' 开头，密码 123456；测试预约用途以 'E2E自动化-' 开头
 *   - 不断言绝对数量，只断言存在 / 可见 / 文案一致
 */
import { test, expect } from '@playwright/test'
import dayjs from 'dayjs'
import { ACCOUNTS, apiLogin, loginAs, clearAuth } from '../helpers/auth'
import { expectOk, listMine, seedApprovedReservation, tryCancel } from '../helpers/api'
import { CLASSROOMS, e2ePurpose, today } from '../helpers/data'

/** 登录按钮文案带字间距（源代码为「登 录」），用正则放宽空白 */
const LOGIN_BTN = /登\s*录/
/** 注册按钮文案（源代码为「注 册」） */
const REGISTER_BTN = /注\s*册/

/** 填写登录表单（角色默认学生=0，登录页初始即为学生） */
async function fillLogin(page, { username, password }) {
  await page.getByPlaceholder('请输入登录账号').fill(username)
  await page.getByPlaceholder('请输入密码').fill(password)
}

/* ------------------------------------------------------------------ */
/* 1. 必填校验                                                         */
/* ------------------------------------------------------------------ */
test('登录页：空表单提交 → 显示必填校验且停留在登录页', async ({ page }) => {
  await clearAuth(page)
  await page.goto('/login')

  await page.getByRole('button', { name: LOGIN_BTN }).click()

  // Login.vue rules：username '请输入登录账号' / password '请输入密码'
  await expect(page.getByText('请输入登录账号')).toBeVisible()
  await expect(page.getByText('请输入密码')).toBeVisible()
  await expect(page).toHaveURL(/\/login/)
})

/* ------------------------------------------------------------------ */
/* 2. 角色单选切换后正常提交                                            */
/* ------------------------------------------------------------------ */
test('登录页：角色切换为「管理员」后提交 → 进入管理端首页', async ({ page }) => {
  await clearAuth(page)
  await page.goto('/login')

  const adminRadio = page.locator('.role-group .el-radio-button', { hasText: '管理员' })
  const studentRadio = page.locator('.role-group .el-radio-button', { hasText: /学\s*生/ })
  // 默认选中学生，切换后管理员应被选中
  await expect(studentRadio.locator('input')).toBeChecked()
  await adminRadio.click()
  await expect(adminRadio.locator('input')).toBeChecked()

  await fillLogin(page, ACCOUNTS.admin)
  await page.getByRole('button', { name: LOGIN_BTN }).click()

  await expect(page).toHaveURL(/\/admin\/home/)
})

/* ------------------------------------------------------------------ */
/* 3. 错误凭证                                                         */
/* ------------------------------------------------------------------ */
test('登录页：不存在的账号 → 错误提示且停留在登录页', async ({ page }) => {
  await clearAuth(page)
  await page.goto('/login')

  // 故意使用不存在的账号，避免对种子账号累计失败次数（后端 5 次失败锁 10 分钟）
  await fillLogin(page, { username: 'e2e_no_such_account', password: 'e2e-wrong-pass' })
  await page.getByRole('button', { name: LOGIN_BTN }).click()

  // UserServiceImpl：账号不存在与密码错误统一返回「账号或密码错误」
  await expect(page.getByText('账号或密码错误')).toBeVisible()
  await expect(page).toHaveURL(/\/login/)
})

test('登录页：已存在账号密码错误 → 错误提示且停留，随后复位失败计数', async ({ page }) => {
  await clearAuth(page)
  await page.goto('/login')

  await fillLogin(page, { username: ACCOUNTS.student2.username, password: 'e2e-not-the-password' })
  await page.getByRole('button', { name: LOGIN_BTN }).click()

  await expect(page.getByText('账号或密码错误')).toBeVisible()
  await expect(page).toHaveURL(/\/login/)

  // 复位：成功登录会清除该账号的失败计数（防止反复跑本用例触发 5 次锁定）
  await apiLogin(page.request, 'student2')
})

/* ------------------------------------------------------------------ */
/* 4. 正确登录                                                         */
/* ------------------------------------------------------------------ */
test('登录页：学生正确登录 → 跳转学生端首页', async ({ page }) => {
  await clearAuth(page)
  await page.goto('/login')

  await fillLogin(page, ACCOUNTS.student)
  await page.getByRole('button', { name: LOGIN_BTN }).click()

  await expect(page).toHaveURL(/\/student\/home/)
})

/* ------------------------------------------------------------------ */
/* 5. 已登录访问 /login 的守卫                                          */
/* ------------------------------------------------------------------ */
test('已登录学生访问 /login → 被跳回学生端首页', async ({ page }) => {
  await loginAs(page, { who: 'student' })
  await page.goto('/login')

  await expect(page).toHaveURL(/\/student\/home/)
})

test('已登录管理员访问 /login → 被跳回管理端首页', async ({ page }) => {
  await loginAs(page, { who: 'admin' })
  await page.goto('/login')

  await expect(page).toHaveURL(/\/admin\/home/)
})

/* ------------------------------------------------------------------ */
/* 6~8. 注册                                                           */
/* ------------------------------------------------------------------ */
test('注册页：两次密码不一致 → 校验提示且停留在注册页', async ({ page }) => {
  await clearAuth(page)
  await page.goto('/register')

  await page.getByPlaceholder('请输入学号').fill('E2E2026001')
  await page.getByPlaceholder('请输入真实姓名').fill('E2E测试学生')
  await page.getByPlaceholder('登录唯一账号').fill('e2e_pwd_mismatch')
  await page.getByPlaceholder('不少于 6 位').fill('123456')
  await page.getByPlaceholder('再次输入密码').fill('1234567')

  await page.getByRole('button', { name: REGISTER_BTN }).click()

  await expect(page.getByText('两次输入的密码不一致')).toBeVisible()
  await expect(page).toHaveURL(/\/register/)
})

test('注册页：账号唯一性 → 用已存在账号 admin 触发被拒', async ({ page }) => {
  await clearAuth(page)
  await page.goto('/register')

  await page.getByPlaceholder('请输入学号').fill('E2E2026002')
  await page.getByPlaceholder('请输入真实姓名').fill('E2E测试学生')
  await page.getByPlaceholder('登录唯一账号').fill(ACCOUNTS.admin.username)
  await page.getByPlaceholder('不少于 6 位').fill('123456')
  await page.getByPlaceholder('再次输入密码').fill('123456')

  await page.getByRole('button', { name: REGISTER_BTN }).click()

  // UserServiceImpl.register：「该账号已被注册，请更换账号」
  await expect(page.getByText('该账号已被注册，请更换账号')).toBeVisible()
  await expect(page).toHaveURL(/\/register/)
})

test('注册页：新账号注册成功 → 可用该账号登录进入学生端', async ({ page }) => {
  const username = `e2e_stu_${Date.now().toString().slice(-9)}`
  await clearAuth(page)
  await page.goto('/register')

  await page.getByPlaceholder('请输入学号').fill(`E2E${Date.now().toString().slice(-9)}`)
  await page.getByPlaceholder('请输入真实姓名').fill('E2E自动化新学生')
  await page.getByPlaceholder('登录唯一账号').fill(username)
  await page.getByPlaceholder('不少于 6 位').fill('123456')
  await page.getByPlaceholder('再次输入密码').fill('123456')

  await page.getByRole('button', { name: REGISTER_BTN }).click()

  await expect(page.getByText('注册成功，请登录')).toBeVisible()
  await expect(page).toHaveURL(/\/login/)

  // 用新账号登录
  await fillLogin(page, { username, password: '123456' })
  await page.getByRole('button', { name: LOGIN_BTN }).click()

  await expect(page).toHaveURL(/\/student\/home/)
})

/* ------------------------------------------------------------------ */
/* 9. 登录后「当日即将开始的预约」提醒（未触发则如实标注）                */
/* ------------------------------------------------------------------ */
test('登录后：当日存在即将开始（24h 内）的已通过预约 → 顶部温和提醒且不阻断跳转', async ({ page }) => {
  const now = dayjs()
  const start = now.add(90, 'minute')
  const end = start.add(60, 'minute')
  // 可造预约窗口：08:00-22:00（后端强校验），且留出 1 小时以上便于清理时取消
  test.skip(
    end.hour() * 60 + end.minute() > 22 * 60 || start.hour() * 60 + start.minute() < 8 * 60,
    `当前时刻（${now.format('HH:mm')}）无法造出「今日且即将开始」的合法预约，本场景无法覆盖`
  )

  // 前置：造一条今日、即将开始（90 分钟后）的已通过预约（按任务约定使用 C101(9) + 今日）
  const admin = await apiLogin(page.request, 'admin')
  const student = await apiLogin(page.request, 'student')
  const id = await seedApprovedReservation(page.request, {
    adminToken: admin.token,
    studentToken: student.token,
    classroomId: CLASSROOMS.C101.id,
    date: today(),
    startTime: start.format('HH:mm'),
    endTime: end.format('HH:mm'),
    purpose: e2ePurpose(`即将开始提醒-${Date.now()}`)
  })

  // 走登录页真实登录，才会执行 Login.vue 的 checkUpcomingReminder
  await clearAuth(page)
  await page.goto('/login')
  await fillLogin(page, ACCOUNTS.student)
  await page.getByRole('button', { name: LOGIN_BTN }).click()

  const alert = page.locator('.el-notification').filter({ hasText: '预约即将开始提醒' })
  const appeared = await alert
    .waitFor({ state: 'visible', timeout: 15000 })
    .then(() => true)
    .catch(() => false)
  if (!appeared) {
    // 提醒未出现时，先用最新 token 清理前置数据，再如实失败（不伪造断言）
    const fresh = await apiLogin(page.request, 'student')
    await tryCancel(page.request, fresh.token, id)
    expect(appeared, '已造出「今日 90 分钟后」的已通过预约，但登录后未出现提醒').toBe(true)
  }

  // 顶部提醒带预约正文（需求 2.4：顶部温和提醒）
  await expect(alert).toContainText('请准时到场')
  // 非阻塞：不出现需点确认的模态框，登录跳转照常完成
  await expect(page.locator('.el-message-box')).toHaveCount(0)
  await expect(page).toHaveURL(/\/student\/home/)

  // 收尾：UI 登录已顶掉旧 token，重新取一次再取消（开始前 90 分钟，允许取消）
  const freshed = await apiLogin(page.request, 'student')
  const after = await listMine(page.request, freshed.token, { page: 1, size: 100 })
  expectOk(after, '登录提醒-收尾查询')
  await tryCancel(page.request, freshed.token, id)
})