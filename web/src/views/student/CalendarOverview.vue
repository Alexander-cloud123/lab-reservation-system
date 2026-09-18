<template>
  <div class="calendar-page">
    <el-card shadow="never">
      <!-- 顶部工具栏：教室筛选 + 视图切换 + 状态图例 -->
      <div class="toolbar">
        <div class="toolbar-left">
          <span class="page-title">预约日历总览</span>
          <el-select
            v-model="selectedClassroomId"
            placeholder="全部教室"
            clearable
            style="width: 220px"
            @change="handleClassroomChange"
          >
            <el-option v-for="c in classrooms" :key="c.id" :label="`${c.name}（${c.roomNo}）`" :value="c.id" />
          </el-select>
        </div>
        <div class="toolbar-right">
          <div class="legend">
            <span class="legend-item"><i class="dot dot-pending" />待审核</span>
            <span class="legend-item"><i class="dot dot-approved" />已通过</span>
            <span class="legend-item"><i class="dot dot-rejected" />已驳回</span>
            <span class="legend-item"><i class="dot dot-canceled" />已取消</span>
          </div>
          <el-radio-group v-model="viewType" size="small" @change="handleViewChange">
            <el-radio-button value="dayGridMonth">月视图</el-radio-button>
            <el-radio-button value="dayGridWeek">周视图</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <div v-loading="loading" class="calendar-container">
        <div ref="calendarEl" />
      </div>
    </el-card>

    <!-- 快速预约弹窗（点击可预约时段发起，前端实时冲突校验 + 后端二次校验） -->
    <el-dialog v-model="reserveVisible" title="快速预约" width="480px" :close-on-click-modal="false">
      <el-form ref="reserveFormRef" :model="reserveForm" :rules="reserveRules" label-width="90px">
        <el-form-item label="教室" prop="classroomId">
          <el-select v-model="reserveForm.classroomId" placeholder="请选择教室" style="width: 100%">
            <el-option v-for="c in classrooms" :key="c.id" :label="`${c.name}（${c.roomNo}）`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="预约日期" prop="reserveDate">
          <el-date-picker
            v-model="reserveForm.reserveDate"
            type="date"
            placeholder="选择日期"
            value-format="YYYY-MM-DD"
            :disabled-date="(d) => d && d.getTime() < Date.now() - 86400000"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="开始时间" prop="startTime">
          <el-time-select
            v-model="reserveForm.startTime"
            start="08:00"
            end="21:00"
            step="01:00"
            placeholder="选择开始时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="结束时间" prop="endTime">
          <el-time-select
            v-model="reserveForm.endTime"
            start="09:00"
            end="22:00"
            step="01:00"
            placeholder="选择结束时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="预约用途" prop="purpose">
          <el-input
            v-model="reserveForm.purpose"
            type="textarea"
            :rows="2"
            maxlength="255"
            placeholder="请填写具体教学 / 实验 / 自习等用途"
          />
        </el-form-item>
        <el-alert
          v-if="conflictInfo"
          :type="conflictInfo.conflict ? 'error' : 'success'"
          :title="conflictInfo.reason"
          :closable="false"
          show-icon
        />
      </el-form>
      <template #footer>
        <el-button @click="reserveVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :disabled="conflictInfo && conflictInfo.conflict"
          @click="handleSubmitReserve"
        >
          提交预约
        </el-button>
      </template>
    </el-dialog>

    <!-- 预约详情弹窗（点击色块查看） -->
    <el-dialog v-model="detailVisible" title="预约详情" width="440px">
      <el-descriptions v-if="detail" :column="1" border>
        <el-descriptions-item label="教室">{{ detail.classroomName }}（{{ detail.roomNo }}）</el-descriptions-item>
        <el-descriptions-item label="日期">{{ detail.reserveDate }}</el-descriptions-item>
        <el-descriptions-item label="时段">{{ detail.startTime }} - {{ detail.endTime }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(detail.status)" size="small">{{ statusText(detail.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.purpose" label="预约用途">{{ detail.purpose }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.auditRemark" label="审核备注">{{ detail.auditRemark }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Calendar } from 'fullcalendar'
import dayGridPlugin from '@fullcalendar/daygrid'
import interactionPlugin from '@fullcalendar/interaction'
import zhCnLocale from '@fullcalendar/core/locales/zh-cn'
import dayjs from 'dayjs'
import { listClassrooms } from '@/api/classroom'
import { checkConflict, submitReservation, getCalendarReservations } from '@/api/reservation'
import { validateBooking } from '@/utils/booking'
import { statusText, statusTagType } from '@/utils/dict'

/** 预约状态常量（与后端 Constants 一致：0-待审核，1-已通过，2-已驳回，3-已取消） */
const RES_STATUS = { PENDING: 0, APPROVED: 1, REJECTED: 2, CANCELED: 3 }

const loading = ref(false)
const classrooms = ref([])
const selectedClassroomId = ref(null)
const viewType = ref('dayGridMonth')

const calendarEl = ref(null)
let calendar = null

/** 当前日历可视区间（ISO 日期，由 datesSet 维护，用于拉取区间预约） */
let rangeStart = ''
let rangeEnd = ''

const reserveVisible = ref(false)
const submitting = ref(false)
const reserveFormRef = ref(null)
const reserveForm = reactive({ classroomId: null, reserveDate: '', startTime: '', endTime: '', purpose: '' })
const conflictInfo = ref(null)

const detailVisible = ref(false)
const detail = ref(null)

const reserveRules = {
  classroomId: [{ required: true, message: '请选择教室', trigger: 'change' }],
  reserveDate: [{ required: true, message: '请选择预约日期', trigger: 'change' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }],
  purpose: [{ required: true, message: '请填写预约用途', trigger: 'blur' }]
}

/** 教室列表（日历筛选 + 快速预约下拉共用） */
async function loadClassrooms() {
  try {
    const res = await listClassrooms({ page: 1, size: 100 })
    classrooms.value = (res.data && res.data.records) || []
  } catch {
    // 统一错误提示已由 request.js 处理
  }
}

/** 拉取当前区间预约 → 渲染色块事件 */
async function loadEvents() {
  if (!rangeStart || !rangeEnd) {
    return
  }
  loading.value = true
  try {
    const params = { startDate: rangeStart, endDate: rangeEnd }
    if (selectedClassroomId.value) {
      params.classroomId = selectedClassroomId.value
    }
    const res = await getCalendarReservations(params)
    const items = (res.data || []).map((r) => toEvent(r))
    calendar.removeAllEvents()
    items.forEach((ev) => calendar.addEvent(ev))
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    loading.value = false
  }
}

/** 预约项 → FullCalendar 事件（色块按状态着色，扩展属性承载详情） */
function toEvent(r) {
  return {
    id: String(r.id),
    title: `${r.classroomName} ${r.startTime}-${r.endTime} ${statusText(r.status)}`,
    start: `${r.reserveDate}T${r.startTime}`,
    end: `${r.reserveDate}T${r.endTime}`,
    allDay: false,
    classNames: [`res-ev-${r.status}`],
    extendedProps: { ...r }
  }
}

/**
 * 事件块自定义渲染（视觉优化轮）：
 * 左色条 + 状态浅底由 CSS 承担（res-ev-{status}），此处只输出「时段 + 教室名」两行；
 * 状态/用途等完整信息保留在原生 title 悬浮详情与点击详情弹窗，避免单靠颜色传达。
 * 使用 DOM 节点 + textContent 赋值，禁止拼接 HTML（防 XSS）。
 */
function renderEventContent(info) {
  const p = info.event.extendedProps || {}
  const root = document.createElement('div')
  root.className = 'res-event-body'

  const time = document.createElement('div')
  time.className = 'res-event-time'
  time.textContent = p.startTime && p.endTime ? `${p.startTime}-${p.endTime}` : ''

  const name = document.createElement('div')
  name.className = 'res-event-name'
  name.textContent = p.classroomName || ''

  root.append(time, name)
  return { domNodes: [root] }
}

/** 教室筛选变化：重新拉取当前区间 */
function handleClassroomChange() {
  loadEvents()
}

/** 视图切换：月/周 */
function handleViewChange(view) {
  if (calendar) {
    calendar.changeView(view)
  }
}

/** 点击空白日期 → 快速预约弹窗（预填日期与教室） */
function handleDateClick(info) {
  reserveForm.classroomId = selectedClassroomId.value || null
  reserveForm.reserveDate = dayjs(info.dateStr).format('YYYY-MM-DD')
  reserveForm.startTime = '08:00'
  reserveForm.endTime = '10:00'
  reserveForm.purpose = ''
  conflictInfo.value = null
  reserveVisible.value = true
}

/** 点击色块 → 查看预约详情 */
function handleEventClick(info) {
  detail.value = info.event.extendedProps || {}
  detailVisible.value = true
}

/**
 * 前端实时冲突校验（双重校验第一层）：
 * 日期/教室/时段齐全时调用冲突检测接口，冲突则禁用提交并提示
 * 竞态保护：每次发请求前自增序号，仅采纳序号最新一次的响应（快速调整时段时旧响应不覆盖新结果）
 */
let conflictSeq = 0
watch(
  () => [reserveForm.classroomId, reserveForm.reserveDate, reserveForm.startTime, reserveForm.endTime],
  async ([classroomId, date, start, end]) => {
    if (!classroomId || !date || !start || !end) {
      conflictInfo.value = null
      return
    }
    const seq = ++conflictSeq
    try {
      const res = await checkConflict({ classroomId, date, startTime: start, endTime: end })
      if (seq !== conflictSeq) {
        // 已有更新的请求发出，丢弃本次过期响应
        return
      }
      conflictInfo.value = res.data
    } catch {
      if (seq !== conflictSeq) {
        return
      }
      conflictInfo.value = null
    }
  }
)

/** 提交快速预约（后端二次冲突检测兜底） */
async function handleSubmitReserve() {
  try {
    await reserveFormRef.value.validate()
  } catch {
    return
  }
  // N3：预约校验统一为公共纯函数（此前该入口连「开始<结束」都没有，补齐后与其他入口同口径）
  const check = validateBooking({
    reserveDate: reserveForm.reserveDate,
    startTime: reserveForm.startTime,
    endTime: reserveForm.endTime
  })
  if (!check.ok) {
    ElMessage.warning(check.message)
    return
  }
  submitting.value = true
  try {
    const res = await submitReservation({
      classroomId: reserveForm.classroomId,
      reserveDate: reserveForm.reserveDate,
      startTime: reserveForm.startTime,
      endTime: reserveForm.endTime,
      purpose: reserveForm.purpose
    })
    ElMessage.success(res.message || '预约提交成功，待管理员审核')
    reserveVisible.value = false
    // 刷新日历色块（新预约进入待审核色块）
    loadEvents()
  } catch {
    // 统一错误提示已由 request.js 处理（含后端冲突拒绝）
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadClassrooms()
  calendar = new Calendar(calendarEl.value, {
    plugins: [dayGridPlugin, interactionPlugin],
    initialView: 'dayGridMonth',
    locale: zhCnLocale,
    height: 'auto',
    firstDay: 1,
    selectable: false,
    displayEventTime: false,
    eventContent: renderEventContent,
    headerToolbar: {
      left: 'prev,next today',
      center: 'title',
      right: ''
    },
    buttonText: { today: '今天' },
    datesSet(info) {
      rangeStart = dayjs(info.start).format('YYYY-MM-DD')
      rangeEnd = dayjs(info.end).format('YYYY-MM-DD')
      loadEvents()
    },
    dateClick: handleDateClick,
    eventClick: handleEventClick,
    eventDidMount(info) {
      // 悬浮显示摘要（浏览器原生 tooltip）
      const ev = info.event
      info.el.title = `${ev.title}${ev.extendedProps && ev.extendedProps.purpose ? '｜' + ev.extendedProps.purpose : ''}`
    }
  })
  calendar.render()
})

onBeforeUnmount(() => {
  if (calendar) {
    calendar.destroy()
    calendar = null
  }
})
</script>

<style scoped>
.calendar-page {
  max-width: 1120px;
  margin: 0 auto;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  flex-wrap: wrap;
  gap: 12px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.legend {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--text-regular);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.dot-pending {
  background: var(--brand-warning);
}

.dot-approved {
  background: var(--brand-success);
}

.dot-rejected {
  background: var(--brand-danger);
}

.dot-canceled {
  background: var(--brand-info);
}

.calendar-container {
  min-height: 420px;
}

/* ============ FullCalendar 主题化（视觉优化轮，依据 docs/日历总览优化-任务提示词.md） ============ */
/* 设计语言：排课表 Timetable —— 三级信息层级（星期表头 → 日期数字 → 事件色块） */
.calendar-container :deep(.fc) {
  font-size: 13px;
  /* FullCalendar 官方 CSS 变量换肤，对齐项目 Design Token */
  --fc-border-color: var(--border-color-light);
  --fc-page-bg-color: #fff;
  --fc-neutral-bg-color: var(--el-fill-color-light);
  --fc-neutral-text-color: var(--text-secondary);
  --fc-small-font-size: 11px;
  --fc-event-border-color: transparent;
  --fc-event-selected-overlay-color: rgba(30, 96, 145, 0.08);
  --fc-button-text-color: var(--text-regular);
  --fc-button-bg-color: #fff;
  --fc-button-border-color: var(--border-color);
  --fc-button-hover-bg-color: var(--brand-primary-light);
  --fc-button-hover-border-color: var(--brand-primary);
  --fc-button-active-bg-color: var(--brand-primary);
  --fc-button-active-border-color: var(--brand-primary);
}

/* 顶部工具栏：标题 + 导航按钮（对齐 Element Plus 小按钮体系） */
.calendar-container :deep(.fc .fc-toolbar) {
  margin-bottom: 10px;
}
.calendar-container :deep(.fc .fc-toolbar-title) {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
}
.calendar-container :deep(.fc .fc-button) {
  border-radius: var(--radius-sm);
  font-size: 12px;
  font-weight: 500;
  padding: 4px 12px;
  line-height: 1.5;
  transition: background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}
.calendar-container :deep(.fc .fc-button:hover) {
  color: var(--brand-primary);
}
.calendar-container :deep(.fc .fc-button:disabled) {
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  border-color: var(--brand-primary);
  opacity: 1;
}

/* 星期表头：浅底 + 加粗；周末弱化 */
.calendar-container :deep(.fc .fc-col-header-cell) {
  padding: 0;
  background: var(--bg-page);
  border-bottom: 1px solid var(--border-color-light);
}
.calendar-container :deep(.fc .fc-col-header-cell-cushion) {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-regular);
  padding: 7px 0;
}
.calendar-container :deep(.fc .fc-col-header-cell.fc-day-sat .fc-col-header-cell-cushion),
.calendar-container :deep(.fc .fc-col-header-cell.fc-day-sun .fc-col-header-cell-cushion) {
  color: var(--text-placeholder);
}

/* 日期格：数字层级 + hover 反馈 */
.calendar-container :deep(.fc .fc-daygrid-day) {
  cursor: pointer;
}
.calendar-container :deep(.fc .fc-daygrid-day-frame) {
  padding: 4px;
}
.calendar-container :deep(.fc .fc-daygrid-day-top) {
  padding: 2px 2px 0;
}
.calendar-container :deep(.fc .fc-daygrid-day-number) {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-regular);
  padding: 2px 5px;
  border-radius: 10px;
  transition: background-color 0.15s ease, color 0.15s ease;
}
.calendar-container :deep(.fc .fc-daygrid-day:hover .fc-daygrid-day-frame) {
  background: var(--brand-primary-lighter);
}
/* 周末 / 跨月日期数字弱化（信息密度管理） */
.calendar-container :deep(.fc .fc-daygrid-day.fc-day-sat .fc-daygrid-day-number),
.calendar-container :deep(.fc .fc-daygrid-day.fc-day-sun .fc-daygrid-day-number),
.calendar-container :deep(.fc .fc-day-other .fc-daygrid-day-number) {
  color: var(--text-placeholder);
}

/* 今日轻量标记：顶部细线 + 数字品牌色圆底 + 极浅底色（Apple/Notion 式 subtle cue） */
.calendar-container :deep(.fc .fc-daygrid-day.fc-day-today) {
  background: var(--brand-primary-lighter);
}
.calendar-container :deep(.fc .fc-daygrid-day.fc-day-today .fc-daygrid-day-frame) {
  position: relative;
}
.calendar-container :deep(.fc .fc-daygrid-day.fc-day-today .fc-daygrid-day-frame::before) {
  content: '';
  position: absolute;
  top: 0;
  left: 6px;
  right: 6px;
  height: 2px;
  border-radius: 1px;
  background: var(--brand-primary);
}
.calendar-container :deep(.fc .fc-daygrid-day.fc-day-today .fc-daygrid-day-number) {
  background: var(--brand-primary);
  color: #fff;
  font-weight: 700;
}

/* 事件块：左色条 + 状态浅底 + 深色文字（扁平纯色，无渐变） */
.calendar-container :deep(.fc .fc-daygrid-event) {
  border-radius: var(--radius-sm);
  border-left: 3px solid var(--ev-bar, var(--brand-info));
  background: var(--ev-bg, var(--brand-info-light));
  padding: 2px 6px;
  cursor: pointer;
  box-shadow: none;
  transition: filter 0.15s ease, box-shadow 0.15s ease;
}
.calendar-container :deep(.fc .fc-daygrid-event:hover) {
  filter: brightness(0.96);
  box-shadow: 0 1px 2px rgba(28, 39, 51, 0.1);
}
.calendar-container :deep(.fc .fc-event-main) {
  overflow: hidden;
}
/* 状态色（与图例 / 状态标签语义一致：0-待审核，1-已通过，2-已驳回，3-已取消） */
.calendar-container :deep(.fc .fc-event.res-ev-0) {
  --ev-bar: var(--brand-warning);
  --ev-bg: var(--brand-warning-light);
}
.calendar-container :deep(.fc .fc-event.res-ev-1) {
  --ev-bar: var(--brand-success);
  --ev-bg: var(--brand-success-light);
}
.calendar-container :deep(.fc .fc-event.res-ev-2) {
  --ev-bar: var(--brand-danger);
  --ev-bg: var(--brand-danger-light);
}
.calendar-container :deep(.fc .fc-event.res-ev-3) {
  --ev-bar: var(--brand-info);
  --ev-bg: var(--brand-info-light);
}

/* 事件块内部两行：时段（等宽数字防跳动）+ 教室名（超长省略号） */
.calendar-container :deep(.res-event-body) {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
  min-width: 0;
}
.calendar-container :deep(.res-event-time) {
  font-size: 10.5px;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.calendar-container :deep(.res-event-name) {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 跨月事件弱化 */
.calendar-container :deep(.fc .fc-day-other .fc-daygrid-event) {
  opacity: 0.65;
}
</style>
