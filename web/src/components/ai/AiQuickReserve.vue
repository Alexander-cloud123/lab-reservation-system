<template>
  <!-- AI 快速预约（需求文档 2.4 教室列表页搜索栏旁入口） -->
  <div class="ai-quick-reserve">
    <el-button v-if="aiEnabled" type="primary" plain @click="openDialog">
      <el-icon class="ai-btn-icon"><MagicStick /></el-icon>AI 快速预约
    </el-button>

    <!-- append-to-body 为必需：本组件被放在教室列表搜索栏的 el-form-item 内，而该搜索栏是 el-form--inline。
         若弹窗不脱离这棵子树，其内部的 el-form-item 会命中两条后代选择器：
         · `.el-form--inline .el-form-item { display:inline-flex; margin-right:32px; vertical-align:middle }`
           → 表单项收缩到内容宽度，width:100% 的 el-select 塌成 44px（教室类型下拉选中的文字不可见）；
         · `.search-card :deep(.el-form-item) { margin-bottom:0 }` → 各表单项间距被清零。
         脱离到 body 后两条规则均不再命中，缓存与解析结果不受影响（scoped 样式按 data-v 属性生效，与 DOM 位置无关）。 -->
    <el-dialog
      v-model="dialogVisible"
      title="AI 快速预约"
      width="640px"
      append-to-body
      :close-on-click-modal="false"
      @close="cancelActiveRequests"
      @closed="resetAll"
    >
      <el-steps :active="step" finish-status="success" simple class="steps">
        <el-step title="描述需求" />
        <el-step title="选择教室" />
        <el-step title="确认提交" />
      </el-steps>

      <!-- 步骤一：自然语言描述 + 结构化解析结果（可编辑，不强制接受） -->
      <div v-if="step === 1" class="step-body">
        <el-input
          v-model="rawText"
          type="textarea"
          :rows="2"
          placeholder="用自然语言描述预约需求，例如：明天下午2点到4点 40人 机房 做课程设计"
        />
        <div class="step-actions">
          <el-button type="primary" :loading="parsing" @click="handleParse">智能解析</el-button>
          <el-button v-if="parsed" @click="resetParse">重新描述</el-button>
        </div>
        <!-- R13-5：phase 进度（仅真正调用模型时出现，缓存命中时解析很快完成不闪文案） -->
        <div v-if="parsing && parsePhase" class="parse-phase">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>{{ parsePhase }}</span>
        </div>

        <el-alert v-if="parseError" type="error" :title="parseError" show-icon :closable="false" class="tip" />
        <el-alert
          v-if="parsed && !parseError"
          type="warning"
          :title="parseNotice || 'AI 生成，仅供参考，可手动修改'"
          show-icon
          :closable="false"
          class="tip"
        />

        <el-form v-if="parsed && !parseError" :model="parseForm" label-width="90px" class="parse-form">
          <el-form-item label="预约日期">
            <el-date-picker
              v-model="parseForm.date"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择日期"
              :disabled-date="disablePastDate"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="时段">
            <el-time-select v-model="parseForm.startTime" start="08:00" end="21:00" step="01:00" placeholder="开始" style="width: 48%" />
            <span class="time-sep">至</span>
            <el-time-select v-model="parseForm.endTime" start="09:00" end="22:00" step="01:00" placeholder="结束" style="width: 48%" />
          </el-form-item>
          <el-form-item label="人数">
            <el-input-number v-model="parseForm.capacity" :min="1" :max="500" />
          </el-form-item>
          <el-form-item label="教室类型">
            <el-select v-model="parseForm.roomType" style="width: 100%">
              <el-option label="不限" value="" />
              <el-option v-for="label in ROOM_TYPE_LABELS" :key="label" :label="label" :value="label" />
            </el-select>
          </el-form-item>
          <el-form-item label="预约用途">
            <el-input v-model="parseForm.purpose" maxlength="255" placeholder="如：课程设计 / 实验 / 自习" />
          </el-form-item>
        </el-form>
      </div>

      <!-- 步骤二：按解析条件筛选可用教室 -->
      <div v-if="step === 2" v-loading="roomsLoading" class="step-body">
        <el-empty v-if="!roomsLoading && !rooms.length" description="未找到符合条件的教室，请返回修改描述" :image-size="80" />
        <div v-for="r in rooms" :key="r.id" class="room-pick-item" v-clickable @click="pickRoom(r)">
          <div class="room-pick-head">
            <span class="room-pick-name">{{ r.name }}</span>
            <el-tag size="small" type="info" effect="plain">{{ typeText(r.type) }}</el-tag>
          </div>
          <div class="room-pick-meta">{{ r.building }} · {{ r.roomNo }} · 容量 {{ r.capacity }} 人</div>
          <div v-if="r.occupiedSlots && r.occupiedSlots.length" class="room-pick-occupied">
            该日期已约 {{ r.occupiedSlots.length }} 个时段，提交时将实时冲突校验
          </div>
        </div>
      </div>

      <!-- 步骤三：确认提交（复用既有提交预约流程：表单校验 + 实时冲突校验 + 后端二次校验） -->
      <div v-if="step === 3" class="step-body">
        <el-alert
          v-if="capacityWarn"
          type="warning"
          :title="capacityWarn"
          show-icon
          :closable="false"
          class="tip"
        />
        <el-form ref="reserveFormRef" :model="reserveForm" :rules="reserveRules" label-width="90px">
          <el-form-item label="教室">
            <span class="picked-room">{{ pickedRoom.name }}（{{ pickedRoom.building }} {{ pickedRoom.roomNo }}）</span>
          </el-form-item>
          <el-form-item label="预约日期" prop="reserveDate">
            <el-date-picker
              v-model="reserveForm.reserveDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="选择日期"
              :disabled-date="disablePastDate"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="开始时间" prop="startTime">
            <el-time-select v-model="reserveForm.startTime" start="08:00" end="21:00" step="01:00" placeholder="开始时间" style="width: 100%" />
          </el-form-item>
          <el-form-item label="结束时间" prop="endTime">
            <el-time-select v-model="reserveForm.endTime" start="09:00" end="22:00" step="01:00" placeholder="结束时间" style="width: 100%" />
          </el-form-item>
          <el-form-item label="预约用途" prop="purpose">
            <el-input v-model="reserveForm.purpose" type="textarea" :rows="2" maxlength="255" placeholder="请填写预约用途" />
          </el-form-item>
          <el-form-item>
            <el-alert
              v-if="conflictChecking"
              type="info"
              title="正在检测时段冲突…"
              :closable="false"
              show-icon
              class="conflict-tip"
            />
            <el-alert
              v-else-if="conflictError"
              type="error"
              :title="conflictError"
              :closable="false"
              show-icon
              class="conflict-tip"
            />
            <el-alert
              v-else-if="conflictDirty"
              type="warning"
              title="预约信息已变化，等待最新冲突检测结果"
              :closable="false"
              show-icon
              class="conflict-tip"
            />
            <el-alert
              v-else-if="conflictInfo"
              :type="conflictInfo.conflict ? 'error' : 'success'"
              :title="conflictInfo.reason"
              :closable="false"
              show-icon
              class="conflict-tip"
            />
          </el-form-item>
        </el-form>
      </div>

      <!-- 底部按钮区（按步骤显示） -->
      <template #footer>
        <el-button v-if="step === 1" @click="dialogVisible = false">取消</el-button>
        <el-button v-if="step === 1" type="primary" :disabled="!(parsed && !parseError)" @click="goStep2">下一步：选择教室</el-button>

        <el-button v-if="step === 2" @click="step = 1">上一步</el-button>
        <el-button v-if="step === 2" @click="dialogVisible = false">取消</el-button>

        <el-button v-if="step === 3" @click="step = 2">上一步</el-button>
        <el-button v-if="step === 3" @click="dialogVisible = false">取消</el-button>
        <el-button
          v-if="step === 3"
          type="primary"
          :loading="submitting"
          :disabled="conflictChecking || !!(conflictInfo && conflictInfo.conflict)"
          @click="handleSubmit"
        >提交预约</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick, Loading } from '@element-plus/icons-vue'
import { aiParseReservationStream } from '@/api/ai'
import { listClassrooms } from '@/api/classroom'
import { checkConflict, submitReservation } from '@/api/reservation'
import { validateBooking } from '@/utils/booking'
import { ROOM_TYPE_LABELS, typeText, typeValue } from '@/utils/dict'

/**
 * AI 快速预约（R7，需求文档 2.4 教室列表页搜索栏旁「AI 快速预约」入口）
 * 自然语言 → AI 解析结构化参数（可编辑）→ 筛选教室 → 复用既有提交预约弹窗流程
 * （前后端双重冲突校验不变）；AI 结果不强制接受，全程可修改/重新描述
 */
defineProps({
  /** AI 是否启用（false 时入口隐藏，系统退化为纯预约系统） */
  aiEnabled: { type: Boolean, default: false }
})

const dialogVisible = ref(false)
const step = ref(1)

/* ===== 步骤一：描述 + 解析 ===== */
const rawText = ref('')
const parsing = ref(false)
const parsed = ref(false)
const parseError = ref('')
/** 解析降级提示（后端 message，非空 = 当前为本地正则规则解析结果，标注「AI 生成」不准确） */
const parseNotice = ref('')
/** R13-5：流式解析阶段进度文案（仅缓存未命中真正调用模型时收到一次） */
const parsePhase = ref('')
const parseForm = reactive({ date: '', startTime: '', endTime: '', capacity: null, roomType: '', purpose: '' })
let parseController = null
let parseSeq = 0

function openDialog() {
  dialogVisible.value = true
}

/** 日期选择以本地当天 00:00 为界，昨天及更早日期均不可选 */
function disablePastDate(date) {
  return !!date && date.getTime() < new Date().setHours(0, 0, 0, 0)
}

function cancelParse() {
  parseSeq += 1
  if (parseController) parseController.abort()
  parseController = null
  parsing.value = false
  parsePhase.value = ''
}

function resetParse() {
  cancelParse()
  parsed.value = false
  parseError.value = ''
  parseNotice.value = ''
  parsePhase.value = ''
  rawText.value = ''
  Object.assign(parseForm, { date: '', startTime: '', endTime: '', capacity: null, roomType: '', purpose: '' })
}

/** 应用 result 帧 VO（与旧非流式 res.data 同构）：关闭态 / 识别失败 / 成功预填三分支 */
function applyParseResult(data) {
  if (!data || data.enabled === false) {
    ElMessage.info('AI 服务未启用')
    return
  }
  if (data.error) {
    parsed.value = false
    parseError.value = '无法识别您的需求，请尝试更具体的描述，例如：明天下午2点到4点 40人 机房 做课程设计'
    return
  }
  // 解析成功 → 预填结构化参数（可编辑）；降级提示透出到顶部标注
  parsed.value = true
  parseNotice.value = data.message || ''
  Object.assign(parseForm, {
    date: data.date || '',
    startTime: data.startTime || '',
    endTime: data.endTime || '',
    capacity: data.capacity || null,
    roomType: data.roomType || '',
    purpose: data.purpose || ''
  })
}

async function handleParse() {
  const question = rawText.value.trim()
  if (!question) {
    ElMessage.warning('请先描述您的预约需求')
    return
  }
  // 新解析先取消旧流；序号确保已排队回调也不能覆盖新描述
  cancelParse()
  const seq = ++parseSeq
  const ctrl = new AbortController()
  parseController = ctrl
  parsing.value = true
  parseError.value = ''
  parseNotice.value = ''
  parsePhase.value = ''
  parsed.value = false
  let parseResult = null
  try {
    // R13-5：SSE 结构化流式；建链前 Result JSON、EOF 异常和绝对超时均走 onError
    await aiParseReservationStream({ text: question }, {
      onPhase: d => {
        if (seq === parseSeq) parsePhase.value = d.text || ''
      },
      onResult: data => {
        if (seq === parseSeq) parseResult = data
      },
      onDone: () => {
        // result 后必须收到 done 才应用，异常 EOF/超时不能留下可提交的半截解析结果。
        if (seq === parseSeq && parseResult) applyParseResult(parseResult)
      },
      onError: message => {
        if (seq !== parseSeq) return
        parseResult = null
        parsed.value = false
        parseError.value = message
        ElMessage.error(message)
      }
    }, ctrl.signal)
  } finally {
    if (seq === parseSeq) {
      parsing.value = false
      parsePhase.value = ''
      parseController = null
    }
  }
}

/* ===== 步骤二：筛选教室 ===== */
const rooms = ref([])
const roomsLoading = ref(false)
let roomsSeq = 0

async function goStep2() {
  const seq = ++roomsSeq
  roomsLoading.value = true
  try {
    const params = { page: 1, size: 500 }
    if (parseForm.date) {
      params.date = parseForm.date
    }
    // 类型文案 → 类型数字（与后端 classroom.type 一致）；未匹配（含「不限」空串）返回 null 跳过筛选
    const t = typeValue(parseForm.roomType)
    if (t !== null) {
      params.type = t
    }
    const res = await listClassrooms(params)
    if (seq !== roomsSeq) return
    let list = (res.data && res.data.records) || []
    if (parseForm.capacity) {
      list = list.filter((r) => r.capacity >= parseForm.capacity)
    }
    rooms.value = list
    step.value = 2
  } catch {
    if (seq !== roomsSeq) return
    // 接口失败：统一错误提示已由 request.js 处理，回退步骤一，可修改描述后重试
    rooms.value = []
    step.value = 1
  } finally {
    if (seq === roomsSeq) roomsLoading.value = false
  }
}

/* ===== 步骤三：确认提交（复用既有提交预约流程）===== */
const pickedRoom = ref(null)
const reserveFormRef = ref(null)
const submitting = ref(false)
const conflictInfo = ref(null)
const conflictChecking = ref(false)
const conflictDirty = ref(false)
const conflictError = ref('')
const reserveForm = reactive({ reserveDate: '', startTime: '', endTime: '', purpose: '' })
let conflictSeq = 0
let activeConflict = null
let checkedSnapshot = ''

const reserveRules = {
  reserveDate: [{ required: true, message: '请选择预约日期', trigger: 'change' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }],
  purpose: [{ required: true, message: '请填写预约用途', trigger: 'blur' }]
}

/** 教室容量不足提示（AI 解析人数仅供参考，不强制） */
const capacityWarn = computed(() => {
  if (pickedRoom.value && parseForm.capacity && pickedRoom.value.capacity < parseForm.capacity) {
    return `该教室容量 ${pickedRoom.value.capacity} 人，小于您填写的 ${parseForm.capacity} 人，请确认是否继续`
  }
  return ''
})

function pickRoom(room) {
  invalidateConflict()
  pickedRoom.value = room
  Object.assign(reserveForm, {
    reserveDate: parseForm.date || '',
    startTime: parseForm.startTime || '',
    endTime: parseForm.endTime || '',
    purpose: parseForm.purpose || ''
  })
  step.value = 3
}

/** 当前冲突检测快照只含判定相关字段；用途不参与冲突计算 */
function conflictSnapshot() {
  if (!pickedRoom.value) return ''
  return JSON.stringify({
    classroomId: pickedRoom.value.id,
    date: reserveForm.reserveDate,
    startTime: reserveForm.startTime,
    endTime: reserveForm.endTime
  })
}

/** 表单变化或弹窗关闭时立即使旧结果失效，旧响应随后到达也无法写回 */
function invalidateConflict() {
  conflictSeq += 1
  activeConflict = null
  checkedSnapshot = ''
  conflictInfo.value = null
  conflictChecking.value = false
  conflictDirty.value = false
  conflictError.value = ''
}

/** 同一快照复用进行中 Promise；不同快照以序号实现 latest-only */
function requestConflictCheck(snapshot = conflictSnapshot()) {
  if (!snapshot) return Promise.resolve(null)
  if (activeConflict && activeConflict.snapshot === snapshot) return activeConflict.promise

  const seq = ++conflictSeq
  const params = JSON.parse(snapshot)
  const request = { seq, snapshot, promise: null }
  conflictChecking.value = true
  conflictDirty.value = true
  conflictError.value = ''
  conflictInfo.value = null
  request.promise = (async () => {
    try {
      const res = await checkConflict(params)
      if (seq !== conflictSeq) return null
      const data = res.data
      if (!data || typeof data.conflict !== 'boolean') {
        checkedSnapshot = ''
        conflictDirty.value = true
        conflictError.value = '冲突检测结果无效，请重试'
        return null
      }
      conflictInfo.value = data
      checkedSnapshot = snapshot
      conflictDirty.value = false
      return data
    } catch {
      if (seq !== conflictSeq) return null
      checkedSnapshot = ''
      conflictInfo.value = null
      conflictDirty.value = true
      conflictError.value = '冲突检测失败，请重试后再提交'
      return null
    } finally {
      if (seq === conflictSeq) {
        conflictChecking.value = false
        activeConflict = null
      }
    }
  })()
  activeConflict = request
  return request.promise
}

/** 换教室或相同日期时段变化都必须重新检测 */
watch(
  () => [pickedRoom.value && pickedRoom.value.id, reserveForm.reserveDate, reserveForm.startTime, reserveForm.endTime],
  ([roomId, date, start, end]) => {
    if (!roomId || !date || !start || !end) {
      invalidateConflict()
      return
    }
    if (start >= end) {
      invalidateConflict()
      conflictInfo.value = { conflict: true, reason: '开始时间必须早于结束时间' }
      return
    }
    const snapshot = JSON.stringify({ classroomId: roomId, date, startTime: start, endTime: end })
    if (snapshot === checkedSnapshot && conflictInfo.value) return
    requestConflictCheck(snapshot)
  }
)

/** 提交前等待并确认当前快照的最新结果；失败/无效结果绝不视为无冲突 */
async function ensureLatestConflict() {
  while (pickedRoom.value) {
    const check = validateBooking({
      reserveDate: reserveForm.reserveDate,
      startTime: reserveForm.startTime,
      endTime: reserveForm.endTime
    })
    if (!check.ok) {
      ElMessage.warning(check.message)
      return false
    }
    const snapshot = conflictSnapshot()
    if (checkedSnapshot === snapshot && conflictInfo.value && !conflictError.value) {
      if (conflictInfo.value.conflict) {
        ElMessage.warning(conflictInfo.value.reason || '当前时段存在冲突')
        return false
      }
      return true
    }
    const result = await requestConflictCheck(snapshot)
    if (snapshot !== conflictSnapshot()) continue
    if (!result) {
      ElMessage.error(conflictError.value || '冲突检测失败，请重试后再提交')
      return false
    }
  }
  return false
}

async function handleSubmit() {
  if (!pickedRoom.value || submitting.value) return
  try {
    await reserveFormRef.value.validate()
  } catch {
    return
  }
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
    if (!(await ensureLatestConflict())) return
    // 后端仍执行二次冲突校验；此处只发送已通过当前快照前端检测的表单
    await submitReservation({
      classroomId: pickedRoom.value.id,
      reserveDate: reserveForm.reserveDate,
      startTime: reserveForm.startTime,
      endTime: reserveForm.endTime,
      purpose: reserveForm.purpose
    })
    ElMessage.success('预约提交成功，等待管理员审核')
    dialogVisible.value = false
  } catch {
    // 统一错误提示（含后端二次冲突校验返回）
  } finally {
    submitting.value = false
  }
}

/** 关闭/卸载立即取消流式解析并使列表、冲突旧响应失效 */
function cancelActiveRequests() {
  cancelParse()
  roomsSeq += 1
  roomsLoading.value = false
  invalidateConflict()
}

/** 关闭弹窗后重置全部状态 */
function resetAll() {
  cancelActiveRequests()
  step.value = 1
  resetParse()
  rooms.value = []
  pickedRoom.value = null
  invalidateConflict()
  Object.assign(reserveForm, { reserveDate: '', startTime: '', endTime: '', purpose: '' })
}

onBeforeUnmount(cancelActiveRequests)
</script>

<style scoped>
.ai-quick-reserve {
  display: inline-block;
}

.steps {
  margin-bottom: 16px;
}

.step-body {
  min-height: 220px;
}

.step-actions {
  margin: 12px 0;
}

.parse-phase {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: -4px 0 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.tip {
  margin-bottom: 12px;
}

.time-sep {
  display: inline-block;
  width: 4%;
  text-align: center;
  color: var(--text-placeholder);
}

.room-pick-item {
  padding: 12px;
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-md);
  margin-bottom: 8px;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.room-pick-item:hover {
  border-color: var(--border-color);
  background: var(--bg-fill);
}

.room-pick-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.room-pick-name {
  font-weight: 500;
  color: var(--text-primary);
}

.room-pick-meta {
  font-size: 12px;
  line-height: 20px;
  color: var(--text-regular);
  margin-top: 4px;
}

.room-pick-occupied {
  font-size: 12px;
  color: var(--brand-warning);
  margin-top: 4px;
}

.picked-room {
  font-weight: 500;
  color: var(--text-primary);
}

.conflict-tip {
  width: 100%;
}

.ai-btn-icon {
  margin-right: 4px;
  font-size: 14px;
}
</style>
