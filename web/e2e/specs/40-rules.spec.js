/**
 * 40-rules —— 核心业务规则回归（答辩重点：核心规则禁止简化）
 *
 * 覆盖范围（需求设计文档 1.4 / 2.3）：
 *   1. 冲突检测公式「新开始 < 旧结束 AND 新结束 > 旧开始」六组边界（左/右重叠、包含、完全重叠、两侧相邻）
 *   2. 仅【已通过(1)】参与冲突判定（待审核、已取消不构成冲突）
 *   3. 后端二次校验：绕过前端直调提交接口，重叠时段仍被拒绝
 *   4. 前端实时校验：教室详情页弹窗冲突提示 + 提交按钮禁用
 *   5. 状态流转：待审核(0)→已通过(1)/已驳回(2)；待审核/已通过→已取消(3)；终态不可再流转
 *   6. 审核通过前复查冲突（防"双已通过"）、批量审核仅待审核可参与 + 驳回必填备注
 *   7. 取消时限：预约开始前 1 小时内禁止取消
 *   8. 分页/日历参数校验（400）
 *   9. 越权：未登录 401、学生访问管理员接口 403；前端路由守卫（角色不符回自己首页）
 *
 * 教室/日期分配（与其他 spec 不重叠，避免互相抢占时段导致假失败）：
 *   C201(10) D10 已通过10:00-12:00 ｜ C301(11) D11 待审核10:00-12:00 ｜ C401(12) D12 已通过10:00-12:00
 *   C301(11) D14 已通过10:00-12:00 ｜ C401(12) D13 10:00-12:00 与 13:00-15:00
 *   C201(10) D14 待审核10:00-12:00 + 已通过14:00-16:00 ｜ C301(11) D13 10:00-12:00
 *   C201(10) D15 A10:00-12:00 / B11:00-13:00 ｜ C301(11) D15 已通过 ｜ C401(12) D15 待审核
 *   C201(10) D16 待审核 ｜ C401(12) D16 10:00-12:00 ｜ 取消时限用 today() + 动态挑空教室
 *
 * ⚠️ 跑完必须清理测试数据，否则重复运行会因残留的"已通过"预约导致造数失败。
 * 注意：docker exec 管道会把中文 LIKE 模式打成乱码（实测 like '%自动化%' 命中 0 行），
 * 必须用 ASCII 前缀匹配（E2E 前缀的 ASCII 部分）：
 *   docker exec tmp-res-mysql mysql -uroot -proot -e "use reservation; delete from reservation where purpose like 'E2E%';"
 */
import { test, expect } from '@playwright/test'
import dayjs from 'dayjs'
import { API_BASE, apiLogin, loginAs, clearAuth } from '../helpers/auth'
import {
  expectOk,
  expectBizFail,
  checkConflict,
  createReservation,
  listMine,
  listManage,
  cancelReservation,
  auditReservation,
  batchAudit,
  listCalendar,
  listClassrooms,
  getOverview,
  seedApprovedReservation
} from '../helpers/api'
import { CLASSROOMS, e2ePurpose, futureDate, today, timeFromNow } from '../helpers/data'

/* ==================== 工具 ==================== */

/** 独立登录取最新 token：用例之间不共享会话，避免同账号再次登录把上一个 token 顶掉 */
async function login(request, who) {
  const data = await apiLogin(request, who)
  return data.token
}

/** 直连接口（api.js 未封装路径用，如导出/管理端只读路径），返回 { status, body, text } */
async function raw(request, token, method, path) {
  const resp = await request.fetch(API_BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    }
  })
  const text = await resp.text()
  let body = null
  try {
    body = JSON.parse(text)
  } catch {
    // 非 JSON（如 Excel 导出流）保留 text
  }
  return { status: resp.status(), body, text }
}

/** 断言鉴权失败：HTTP 状态码与响应体 code 双一致（拦截器写的是真 HTTP 401/403） */
function expectAuthFail(res, code, ctx) {
  expect(res.status, `${ctx}：期望 HTTP ${code}，实际 ${res.status} ${res.text.slice(0, 120)}`).toBe(code)
  expect(res.body && res.body.code, `${ctx}：期望响应体 code=${code}，实际 ${res.text.slice(0, 120)}`).toBe(code)
}

/** 从分页结果里按 id 找记录 */
function findRecord(pageData, id) {
  return (pageData.records || []).find((r) => Number(r.id) === Number(id))
}

/** 从候选教室中挑一个该时段可用的（今日场景用，避免撞上库中既有已通过预约） */
async function pickFreeRoom(request, token, candidates, date, startTime, endTime) {
  for (const room of candidates) {
    const data = expectOk(
      await checkConflict(request, token, { classroomId: room.id, date, startTime, endTime }),
      `挑空教室-冲突检测（C=${room.id}）`
    )
    if (!data.conflict) {
      return room
    }
  }
  return null
}

/* ==================== 1. 冲突检测公式 ==================== */

test.describe('预约冲突检测（核心公式）', () => {
  test('六组边界：仅与「已通过」预约按重叠公式判定', async ({ request }) => {
    const adminToken = await login(request, 'admin')
    const studentToken = await login(request, 'student')
    const room = CLASSROOMS.C201
    const date = futureDate(10)

    // 基准：已通过预约 10:00-12:00（按真实链路造：提交 → 管理员审核通过）
    await seedApprovedReservation(request, {
      adminToken,
      studentToken,
      classroomId: room.id,
      date,
      startTime: '10:00',
      endTime: '12:00',
      purpose: e2ePurpose(`冲突基准-${room.name}-10:00-12:00`)
    })

    const cases = [
      { start: '09:00', end: '11:00', conflict: true, label: '左侧部分重叠' },
      { start: '11:00', end: '13:00', conflict: true, label: '右侧部分重叠' },
      { start: '09:00', end: '13:00', conflict: true, label: '被既有完全包含' },
      { start: '10:00', end: '12:00', conflict: true, label: '完全重叠' },
      { start: '08:00', end: '10:00', conflict: false, label: '左相邻（旧结束==新开始）' },
      { start: '12:00', end: '14:00', conflict: false, label: '右相邻（新开始==旧结束）' }
    ]

    for (const c of cases) {
      await test.step(`${c.label} ${c.start}-${c.end} → ${c.conflict ? '冲突' : '可预约'}`, async () => {
        const data = expectOk(
          await checkConflict(request, studentToken, {
            classroomId: room.id,
            date,
            startTime: c.start,
            endTime: c.end
          }),
          `冲突检测 ${c.start}-${c.end}`
        )
        expect(data.conflict).toBe(c.conflict)
        if (c.conflict) {
          expect(data.reason).toContain('冲突')
          expect(data.reason).toContain('10:00-12:00')
        } else {
          expect(data.reason).toBe('该时段可预约')
        }
      })
    }
  })

  test('待审核 / 已取消的预约不构成冲突（仅已通过参与判定）', async ({ request }) => {
    const studentToken = await login(request, 'student')
    const room = CLASSROOMS.C301
    const date = futureDate(11)
    const slot = { classroomId: room.id, date, startTime: '10:00', endTime: '12:00' }

    // 造一条待审核（不审核）
    const id = expectOk(
      await createReservation(request, studentToken, {
        classroomId: room.id,
        reserveDate: date,
        startTime: '10:00',
        endTime: '12:00',
        purpose: e2ePurpose('仅已通过才冲突-待审核')
      }),
      '待审核预约提交'
    )

    const pendingCheck = expectOk(
      await checkConflict(request, studentToken, slot),
      '待审核存续时的冲突检测'
    )
    expect(pendingCheck.conflict, '待审核不应构成冲突').toBe(false)

    // 取消后同样不构成冲突
    expectOk(await cancelReservation(request, studentToken, id), '取消待审核预约')
    const canceledCheck = expectOk(
      await checkConflict(request, studentToken, slot),
      '已取消后的冲突检测'
    )
    expect(canceledCheck.conflict, '已取消不应构成冲突').toBe(false)
  })

  test('后端二次校验：绕过前端直调提交接口，重叠时段仍被拒绝', async ({ request }) => {
    const adminToken = await login(request, 'admin')
    const studentToken = await login(request, 'student')
    const room = CLASSROOMS.C401
    const date = futureDate(12)

    await seedApprovedReservation(request, {
      adminToken,
      studentToken,
      classroomId: room.id,
      date,
      startTime: '10:00',
      endTime: '12:00',
      purpose: e2ePurpose(`二次校验基准-${room.name}`)
    })

    // 重叠 → 业务失败 400（前端校验被绕过仍被后端拦下）
    const conflictRes = await createReservation(request, studentToken, {
      classroomId: room.id,
      reserveDate: date,
      startTime: '11:00',
      endTime: '13:00',
      purpose: e2ePurpose('二次校验-重叠时段应被拒')
    })
    const fail = expectBizFail(conflictRes, 400, '直调提交重叠时段')
    expect(fail.message).toContain('冲突')

    // 对照组：相邻不重叠 → 提交成功
    const okId = expectOk(
      await createReservation(request, studentToken, {
        classroomId: room.id,
        reserveDate: date,
        startTime: '12:00',
        endTime: '14:00',
        purpose: e2ePurpose('二次校验-相邻时段应放行')
      }),
      '直调提交相邻时段'
    )
    expect(okId).toBeTruthy()
    await cancelReservation(request, studentToken, okId)
  })
})

/* ==================== 2. 前端实时校验 ==================== */

test.describe('前端实时校验（教室详情页）', () => {
  test('重叠时段提示冲突且按钮禁用，改为不冲突时段后按钮可用', async ({ page, request }) => {
    const adminToken = await login(request, 'admin')
    const studentData = await apiLogin(page.request, 'student')
    const room = CLASSROOMS.C301
    const date = futureDate(14)

    // 前置：该教室该日期已有一条已通过 10:00-12:00
    await seedApprovedReservation(request, {
      adminToken,
      studentToken: studentData.token,
      classroomId: room.id,
      date,
      startTime: '10:00',
      endTime: '12:00',
      purpose: e2ePurpose('前端校验基准-10:00-12:00')
    })

    // 借"预约草稿回填"（应用自身功能）把弹窗表单预置为指定时段，避免日期/时间选择器的交互抖动
    const draftKey = `reservation_draft_${studentData.user.id}_${room.id}`
    const setDraft = (startTime, endTime) =>
      page.addInitScript(
        ([k, v]) => localStorage.setItem(k, v),
        [draftKey, JSON.stringify({ reserveDate: date, startTime, endTime, purpose: e2ePurpose('前端实时校验') })]
      )

    await loginAs(page, { who: 'student' })
    await setDraft('10:00', '12:00')
    await page.goto(`/student/classrooms/${room.id}`)

    // 等详情加载完成（教室名渲染出来）后再打开弹窗，保证冲突检测带得上 classroomId
    await expect(page.locator('.room-name-wrap h2')).not.toBeEmpty()
    await page.locator('.action-bar').getByRole('button', { name: '预约申请' }).click()

    // 重叠：提示冲突 + 提交按钮禁用
    await expect(page.locator('.conflict-alert')).toContainText('冲突')
    await expect(page.locator('.conflict-alert')).toContainText('10:00-12:00')
    const submitBtn = page.locator('.el-dialog__footer').getByRole('button', { name: '提交预约' })
    await expect(submitBtn).toBeDisabled()

    // 关闭（未提交，走草稿保存）后改为不冲突时段，重新打开
    await page.locator('.el-dialog__footer').getByRole('button', { name: '取消' }).click()
    await expect(page.locator('.el-dialog')).toBeHidden()
    await page.evaluate(
      ([k, v]) => localStorage.setItem(k, v),
      [draftKey, JSON.stringify({ reserveDate: date, startTime: '12:00', endTime: '14:00', purpose: e2ePurpose('前端实时校验') })]
    )
    await page.locator('.action-bar').getByRole('button', { name: '预约申请' }).click()

    // 不冲突：提示可预约 + 提交按钮可用
    await expect(page.locator('.conflict-alert')).toContainText('该时段可预约')
    await expect(submitBtn).toBeEnabled()
  })

  test('开始时间不早于结束时间：前端提示且不发冲突检测请求', async ({ page }) => {
    const studentData = await apiLogin(page.request, 'student')
    const room = CLASSROOMS.C201
    const date = futureDate(10)
    const draftKey = `reservation_draft_${studentData.user.id}_${room.id}`

    await loginAs(page, { who: 'student' })
    await page.addInitScript(
      ([k, v]) => localStorage.setItem(k, v),
      [draftKey, JSON.stringify({ reserveDate: date, startTime: '14:00', endTime: '14:00', purpose: e2ePurpose('时间倒置') })]
    )
    await page.goto(`/student/classrooms/${room.id}`)

    await expect(page.locator('.room-name-wrap h2')).not.toBeEmpty()
    await page.locator('.action-bar').getByRole('button', { name: '预约申请' }).click()

    // 仅断言前端给出时间倒置提示；提交按钮的 disabled 只跟随"冲突"结果，此处不断言以免锁死实现细节
    await expect(page.locator('.conflict-alert')).toContainText('开始时间必须早于结束时间')
  })
})

/* ==================== 3. 状态流转 ==================== */

test.describe('预约状态流转（待审核0 / 已通过1 / 已驳回2 / 已取消3）', () => {
  test('待审核 → 已通过：审核后落库为已通过，且不可重复审核', async ({ request }) => {
    const adminToken = await login(request, 'admin')
    const studentToken = await login(request, 'student')
    const room = CLASSROOMS.C401
    const date = futureDate(13)

    const id = expectOk(
      await createReservation(request, studentToken, {
        classroomId: room.id,
        reserveDate: date,
        startTime: '10:00',
        endTime: '12:00',
        purpose: e2ePurpose('流转-0→1')
      }),
      '待审核预约提交'
    )

    expectOk(await auditReservation(request, adminToken, id, 1), '审核通过')

    const mine = expectOk(await listMine(request, studentToken, { page: 1, size: 100, status: 1 }), '我的已通过列表')
    const rec = findRecord(mine, id)
    expect(rec, '审核通过后应出现在「已通过」列表中').toBeTruthy()
    expect(rec.status).toBe(1)

    // 已通过不是待审核 → 不可再审核
    const again = await auditReservation(request, adminToken, id, 2, e2ePurpose('不应生效'))
    const fail = expectBizFail(again, 400, '重复审核已通过记录')
    expect(fail.message).toContain('仅待审核状态的预约可审核')
  })

  test('待审核 → 已驳回：驳回必填备注，驳回后不可再审', async ({ request }) => {
    const adminToken = await login(request, 'admin')
    const studentToken = await login(request, 'student')
    const room = CLASSROOMS.C401
    const date = futureDate(13)

    const id = expectOk(
      await createReservation(request, studentToken, {
        classroomId: room.id,
        reserveDate: date,
        startTime: '13:00',
        endTime: '15:00',
        purpose: e2ePurpose('流转-0→2')
      }),
      '待审核预约提交（驳回用）'
    )

    // 驳回不填备注 → 拒绝
    const noRemark = await auditReservation(request, adminToken, id, 2)
    const fail = expectBizFail(noRemark, 400, '驳回未填备注')
    expect(fail.message).toContain('驳回必须填写审核备注')

    // 填备注 → 驳回成功
    expectOk(await auditReservation(request, adminToken, id, 2, e2ePurpose('不满足教学安排')), '驳回（带备注）')

    const mine = expectOk(await listMine(request, studentToken, { page: 1, size: 100, status: 2 }), '我的已驳回列表')
    const rec = findRecord(mine, id)
    expect(rec, '驳回后应出现在「已驳回」列表中').toBeTruthy()
    expect(rec.status).toBe(2)

    const again = await auditReservation(request, adminToken, id, 1)
    expectBizFail(again, 400, '再审已驳回记录')
  })

  test('待审核 / 已通过 → 已取消；已取消不可再取消', async ({ request }) => {
    const adminToken = await login(request, 'admin')
    const studentToken = await login(request, 'student')
    const room = CLASSROOMS.C201
    const date = futureDate(14)

    // 待审核 → 已取消
    const pendingId = expectOk(
      await createReservation(request, studentToken, {
        classroomId: room.id,
        reserveDate: date,
        startTime: '10:00',
        endTime: '12:00',
        purpose: e2ePurpose('流转-待审核→取消')
      }),
      '待审核预约提交（取消用）'
    )
    expectOk(await cancelReservation(request, studentToken, pendingId), '取消待审核预约')
    const recancel = await cancelReservation(request, studentToken, pendingId)
    const fail = expectBizFail(recancel, 400, '重复取消')
    expect(fail.message).toContain('当前状态不可取消')

    // 已通过 → 已取消
    const approvedId = await seedApprovedReservation(request, {
      adminToken,
      studentToken,
      classroomId: room.id,
      date,
      startTime: '14:00',
      endTime: '16:00',
      purpose: e2ePurpose('流转-已通过→取消')
    })
    expectOk(await cancelReservation(request, studentToken, approvedId), '取消已通过预约')

    const mine = expectOk(await listMine(request, studentToken, { page: 1, size: 100, status: 3 }), '我的已取消列表')
    expect(findRecord(mine, approvedId), '已通过取消后应出现在「已取消」列表中').toBeTruthy()
  })

  test('只能取消自己的预约（跨用户被拒）', async ({ request }) => {
    const studentToken = await login(request, 'student')
    const student2Token = await login(request, 'student2')

    const id = expectOk(
      await createReservation(request, student2Token, {
        classroomId: CLASSROOMS.C301.id,
        reserveDate: futureDate(13),
        startTime: '10:00',
        endTime: '12:00',
        purpose: e2ePurpose('跨用户取消-他人预约')
      }),
      'student2 提交预约'
    )

    const fail = expectBizFail(await cancelReservation(request, studentToken, id), 400, '取消他人预约')
    expect(fail.message).toContain('只能取消自己的预约')

    // 收尾：本人取消
    expectOk(await cancelReservation(request, student2Token, id), 'student2 自行取消')
  })
})

/* ==================== 4. 审核复查冲突 与 批量审核 ==================== */

test.describe('审核冲突复查与批量审核', () => {
  test('审核通过前复查冲突：同教室同时段先后通过，第二条被拒（防「双已通过」）', async ({ request }) => {
    const adminToken = await login(request, 'admin')
    const studentToken = await login(request, 'student')
    const room = CLASSROOMS.C201
    const date = futureDate(15)

    // 两条重叠的待审核（此时都还不是已通过，提交阶段都不冲突）
    const idA = expectOk(
      await createReservation(request, studentToken, {
        classroomId: room.id,
        reserveDate: date,
        startTime: '10:00',
        endTime: '12:00',
        purpose: e2ePurpose('双已通过-A')
      }),
      '提交 A'
    )
    const idB = expectOk(
      await createReservation(request, studentToken, {
        classroomId: room.id,
        reserveDate: date,
        startTime: '11:00',
        endTime: '13:00',
        purpose: e2ePurpose('双已通过-B')
      }),
      '提交 B'
    )

    expectOk(await auditReservation(request, adminToken, idA, 1), '通过 A')

    const fail = expectBizFail(await auditReservation(request, adminToken, idB, 1), 400, '通过重叠的 B')
    expect(fail.message).toContain('冲突')
    expect(fail.message).toContain('不能通过')

    // 收尾：B 仍为待审核，由本人取消
    expectOk(await cancelReservation(request, studentToken, idB), '取消 B')
  })

  test('批量审核：仅待审核参与，已通过记录不被改动，返回实际更新条数', async ({ request }) => {
    const adminToken = await login(request, 'admin')
    const studentToken = await login(request, 'student')
    const d15 = futureDate(15)
    const d16 = futureDate(16)

    const approvedId = await seedApprovedReservation(request, {
      adminToken,
      studentToken,
      classroomId: CLASSROOMS.C301.id,
      date: d15,
      startTime: '10:00',
      endTime: '12:00',
      purpose: e2ePurpose('批量审核-已通过不参与')
    })
    const pending1 = expectOk(
      await createReservation(request, studentToken, {
        classroomId: CLASSROOMS.C401.id,
        reserveDate: d15,
        startTime: '10:00',
        endTime: '12:00',
        purpose: e2ePurpose('批量审核-待审核1')
      }),
      '提交待审核 1'
    )
    const pending2 = expectOk(
      await createReservation(request, studentToken, {
        classroomId: CLASSROOMS.C201.id,
        reserveDate: d16,
        startTime: '10:00',
        endTime: '12:00',
        purpose: e2ePurpose('批量审核-待审核2')
      }),
      '提交待审核 2'
    )

    const updated = expectOk(
      await batchAudit(request, adminToken, [approvedId, pending1, pending2], 1),
      '批量通过（混入已通过记录）'
    )
    expect(updated, '只有 2 条待审核被更新，已通过那条不参与').toBe(2)

    const mine = expectOk(await listMine(request, studentToken, { page: 1, size: 100, status: 1 }), '我的已通过列表')
    expect(findRecord(mine, pending1), '待审核 1 应变为已通过').toBeTruthy()
    expect(findRecord(mine, pending2), '待审核 2 应变为已通过').toBeTruthy()
    expect(findRecord(mine, approvedId), '原本已通过的记录仍在已通过列表').toBeTruthy()
  })

  test('批量驳回必须填写审核备注', async ({ request }) => {
    const adminToken = await login(request, 'admin')
    const studentToken = await login(request, 'student')

    const id = expectOk(
      await createReservation(request, studentToken, {
        classroomId: CLASSROOMS.C401.id,
        reserveDate: futureDate(16),
        startTime: '10:00',
        endTime: '12:00',
        purpose: e2ePurpose('批量驳回-未填备注')
      }),
      '提交（批量驳回复用）'
    )

    const fail = expectBizFail(await batchAudit(request, adminToken, [id], 2), 400, '批量驳回未填备注')
    expect(fail.message).toContain('驳回必须填写审核备注')

    const updated = expectOk(
      await batchAudit(request, adminToken, [id], 2, e2ePurpose('批量驳回-备注')), 
      '批量驳回（带备注）'
    )
    expect(updated).toBe(1)
  })
})

/* ==================== 5. 取消时限 ==================== */

test.describe('取消时限（开始前 1 小时内禁止取消）', () => {
  test('开始前 1 小时内禁止取消，超过 1 小时可取消', async ({ request }) => {
    const studentToken = await login(request, 'student')
    const date = today()
    const hour = dayjs().hour()
    // 安全窗口：08:00-18:59 —— 保证 +30/+180 分钟都不跨天、不越出 08:00-22:00 可预约窗口
    test.skip(hour < 8 || hour > 18, '当前时刻超出安全窗口（08:00-18:59），跳过以免跨天/越窗造成误判')

    const candidates = [CLASSROOMS.C201, CLASSROOMS.C301, CLASSROOMS.C401]

    // 场景一：30 分钟后开始 → 落在"开始前 1 小时内"，禁止取消
    const soonStart = timeFromNow(30)
    const soonEnd = timeFromNow(90)
    const soonRoom = await pickFreeRoom(request, studentToken, candidates, date, soonStart, soonEnd)
    test.skip(!soonRoom, '今日候选教室该时段均已被占用，跳过')
    const soonId = expectOk(
      await createReservation(request, studentToken, {
        classroomId: soonRoom.id,
        reserveDate: date,
        startTime: soonStart,
        endTime: soonEnd,
        purpose: e2ePurpose('取消时限-1小时内')
      }),
      '提交 30 分钟后开始的预约'
    )
    const fail = expectBizFail(await cancelReservation(request, studentToken, soonId), 400, '1 小时内取消')
    expect(fail.message).toContain('禁止取消')

    // 场景二：120 分钟后开始 → 超过 1 小时，可取消
    const laterStart = timeFromNow(120)
    const laterEnd = timeFromNow(180)
    const laterRoom = await pickFreeRoom(request, studentToken, candidates, date, laterStart, laterEnd)
    test.skip(!laterRoom, '今日候选教室该时段均已被占用，跳过')
    const laterId = expectOk(
      await createReservation(request, studentToken, {
        classroomId: laterRoom.id,
        reserveDate: date,
        startTime: laterStart,
        endTime: laterEnd,
        purpose: e2ePurpose('取消时限-超1小时')
      }),
      '提交 120 分钟后开始的预约'
    )
    expectOk(await cancelReservation(request, studentToken, laterId), '超过 1 小时取消应成功')
  })
})

/* ==================== 6. 参数校验 ==================== */

test.describe('参数校验（分页 / 日历）', () => {
  test('分页参数：page<1 与 size>500 均返回 400', async ({ request }) => {
    const studentToken = await login(request, 'student')

    const pageFail = expectBizFail(
      await listMine(request, studentToken, { page: 0, size: 10 }),
      400,
      'page=0'
    )
    expect(pageFail.message).toBe('页码必须大于等于 1')

    const sizeFail = expectBizFail(
      await listMine(request, studentToken, { page: 1, size: 501 }),
      400,
      'size=501'
    )
    expect(sizeFail.message).toBe('每页条数必须在 1-500 之间')

    // 同一校验器在所有分页接口生效：教室列表也拦
    const roomFail = expectBizFail(await listClassrooms(request, studentToken, { page: 0, size: 10 }), 400, '教室列表 page=0')
    expect(roomFail.message).toBe('页码必须大于等于 1')
  })

  test('日历区间：缺参 / 区间倒置 / 跨度过大均返回 400', async ({ request }) => {
    const studentToken = await login(request, 'student')

    const missing = expectBizFail(await listCalendar(request, studentToken, {}), 400, '日历缺 startDate')
    expect(missing.message).toContain('startDate')

    const reversed = expectBizFail(
      await listCalendar(request, studentToken, { startDate: futureDate(5), endDate: today() }),
      400,
      '日历区间倒置'
    )
    expect(reversed.message).toContain('开始日期不能晚于结束日期')

    const tooLong = expectBizFail(
      await listCalendar(request, studentToken, { startDate: today(), endDate: futureDate(500) }),
      400,
      '日历跨度过大'
    )
    expect(tooLong.message).toContain('366')
  })
})

/* ==================== 7. 越权与路由守卫 ==================== */

test.describe('越权（401 / 403）与前端路由守卫', () => {
  test('未登录访问受保护接口 → HTTP 401', async ({ request }) => {
    const paths = [
      { method: 'GET', path: '/api/reservation/mine?page=1&size=10' },
      { method: 'GET', path: '/api/classroom/list?page=1&size=10' },
      { method: 'GET', path: '/api/stats/overview' },
      { method: 'GET', path: '/api/user/info' }
    ]
    for (const p of paths) {
      const res = await raw(request, null, p.method, p.path)
      expectAuthFail(res, 401, `未登录访问 ${p.path}`)
    }

    // 伪造 Token 同样按未登录处理
    const forged = await raw(request, 'not-a-real-token', 'GET', '/api/reservation/mine?page=1&size=10')
    expectAuthFail(forged, 401, '伪造 Token')
  })

  test('学生 Token 访问管理员专属接口 → HTTP 403', async ({ request }) => {
    const studentToken = await login(request, 'student')
    const endpoints = [
      { method: 'GET', path: '/api/user/manage?page=1&size=10' },
      { method: 'GET', path: '/api/classroom/manage?page=1&size=10' },
      { method: 'GET', path: '/api/reservation/manage?page=1&size=10' },
      { method: 'GET', path: '/api/reservation/export' },
      { method: 'GET', path: '/api/stats/overview' },
      { method: 'PUT', path: '/api/reservation/1/audit' },
      { method: 'POST', path: '/api/reservation/batch-audit' }
    ]
    for (const e of endpoints) {
      const res = await raw(request, studentToken, e.method, e.path)
      expectAuthFail(res, 403, `学生访问 ${e.method} ${e.path}`)
    }
  })

  test('管理员 Token 访问学生端接口正常放行', async ({ request }) => {
    const adminToken = await login(request, 'admin')
    expectOk(await listClassrooms(request, adminToken, { page: 1, size: 5 }), '管理员浏览教室列表')
    expectOk(await listMine(request, adminToken, { page: 1, size: 5 }), '管理员查看我的预约')
    const overview = expectOk(await getOverview(request, adminToken), '管理员首页概览')
    expect(typeof overview.todayReservationCount).toBe('number')
    expect(typeof overview.pendingAuditCount).toBe('number')
    expect(typeof overview.classroomCount).toBe('number')
    expect(typeof overview.userCount).toBe('number')
  })

  test('前端路由守卫：未登录访问受保护页面跳登录页并带回跳地址', async ({ page }) => {
    await clearAuth(page)
    await page.goto('/admin/home')
    await expect(page).toHaveURL(/\/login/)
    expect(new URL(page.url()).searchParams.get('redirect')).toBe('/admin/home')
  })

  test('前端路由守卫：角色不符访问对方端页面回自己首页', async ({ page }) => {
    // 学生 → 管理端页面
    await loginAs(page, { who: 'student' })
    await page.goto('/admin/home')
    await expect(page).toHaveURL(/\/student\/home$/)

    // 管理员 → 学生端页面
    await loginAs(page, { who: 'admin' })
    await page.goto('/student/my-reservations')
    await expect(page).toHaveURL(/\/admin\/home$/)
  })
})