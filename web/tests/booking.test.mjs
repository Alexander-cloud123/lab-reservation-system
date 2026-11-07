import test from 'node:test'
import assert from 'node:assert/strict'
import { validateBooking } from '../src/utils/booking.js'

const now = new Date(2026, 10, 7, 9, 30, 0)

function check(reserveDate, startTime = '10:00', endTime = '11:00') {
  return validateBooking({ reserveDate, startTime, endTime, now })
}

test('拒绝昨天及更早日期', () => {
  assert.equal(check('2026-11-06').ok, false)
  assert.equal(check('2020-01-01').ok, false)
})

test('拒绝今天已经过去的开始时刻，允许今天稍后的时段', () => {
  assert.equal(check('2026-11-07', '09:00', '10:00').ok, false)
  assert.equal(check('2026-11-07', '10:00', '11:00').ok, true)
})

test('允许未来合法时段，保留负责人确认的窗口与 8 小时上限', () => {
  assert.equal(check('2026-11-08', '08:00', '16:00').ok, true)
  assert.equal(check('2026-11-08', '07:00', '08:00').ok, false)
  assert.equal(check('2026-11-08', '08:00', '17:00').ok, false)
})

test('拒绝相同或倒置的起止时间', () => {
  assert.equal(check('2026-11-08', '10:00', '10:00').ok, false)
  assert.equal(check('2026-11-08', '11:00', '10:00').ok, false)
})
