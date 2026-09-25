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
    <el-dialog v-model="dialogVisible" title="AI 快速预约" width="640px" append-to-body :close-on-click-modal="false" @closed="resetAll">
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

        <el-alert v-if="parseError" type="error" :title="parseError" show-icon :closable="false" class="tip" />
        <el-alert
          v-if="parsed && !parseError"
          type="warning"
          title="AI 生成，仅供参考，可手动修改"
          show-icon
          :closable="false"
          class="tip"
        />

        <el-form v-if="parsed && !parseError" :model="parseForm" label-width="90px" class="parse-form">
          <el-form-item label="预约日期">
            <el-date-picker v-model="parseForm.date" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" />
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
            <el-date-picker v-model="reserveForm.reserveDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" />
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
              v-if="conflictInfo"
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
          :disabled="!!(conflictInfo && conflictInfo.conflict)"
          @click="handleSubmit"
        >提交预约</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick } from '@element-plus/icons-vue'
import { aiParseReservation } from '@/api/ai'
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
const parseForm = reactive({ date: '', startTime: '', endTime: '', capacity: null, roomType: '', purpose: '' })

function openDialog() {
  dialogVisible.value = true
}

function resetParse() {
  parsed.value = false
  parseError.value = ''
  rawText.value = ''
  Object.assign(parseForm, { date: '', startTime: '', endTime: '', capacity: null, roomType: '', purpose: '' })
}

async function handleParse() {
  if (!rawText.value.trim()) {
    ElMessage.warning('请先描述您的预约需求')
    return
  }
  parsing.value = true
  parseError.value = ''
  try {
    const res = await aiParseReservation({ text: rawText.value.trim() })
    if (res.data && res.data.enabled === false) {
      ElMessage.info('AI 服务未启用')
      return
    }
    if (res.data && res.data.error) {
      parsed.value = false
      parseError.value = '无法识别您的需求，请尝试更具体的描述，例如：明天下午2点到4点 40人 机房 做课程设计'
      return
    }
    // 解析成功 → 预填结构化参数（可编辑）
    parsed.value = true
    Object.assign(parseForm, {
      date: res.data.date || '',
      startTime: res.data.startTime || '',
      endTime: res.data.endTime || '',
      capacity: res.data.capacity || null,
      roomType: res.data.roomType || '',
      purpose: res.data.purpose || ''
    })
  } catch {
    // 统一错误提示已由 request.js 处理
    parsed.value = false
  } finally {
    parsing.value = false
  }
}

/* ===== 步骤二：筛选教室 ===== */
const rooms = ref([])
const roomsLoading = ref(false)

async function goStep2() {
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
    let list = (res.data && res.data.records) || []
    if (parseForm.capacity) {
      list = list.filter((r) => r.capacity >= parseForm.capacity)
    }
    rooms.value = list
    step.value = 2
  } catch {
    // 接口失败：统一错误提示已由 request.js 处理，回退步骤一，可修改描述后重试
    rooms.value = []
    step.value = 1
  } finally {
    roomsLoading.value = false
  }
}

/* ===== 步骤三：确认提交（复用既有提交预约流程）===== */
const pickedRoom = ref(null)
const reserveFormRef = ref(null)
const submitting = ref(false)
const conflictInfo = ref(null)
const reserveForm = reactive({ reserveDate: '', startTime: '', endTime: '', purpose: '' })

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
  pickedRoom.value = room
  conflictInfo.value = null
  Object.assign(reserveForm, {
    reserveDate: parseForm.date || '',
    startTime: parseForm.startTime || '',
    endTime: parseForm.endTime || '',
    purpose: parseForm.purpose || ''
  })
  step.value = 3
}

/** 实时冲突校验（与既有提交弹窗同口径：前端校验 + 后端提交时二次校验） */
watch(
  () => [reserveForm.reserveDate, reserveForm.startTime, reserveForm.endTime],
  async () => {
    if (!pickedRoom.value || !reserveForm.reserveDate || !reserveForm.startTime || !reserveForm.endTime) {
      conflictInfo.value = null
      return
    }
    // 前端时间合理性校验：开始时间必须早于结束时间（L9 优化，后端仍强制兜底）
    if (reserveForm.startTime >= reserveForm.endTime) {
      conflictInfo.value = { conflict: true, reason: '开始时间必须早于结束时间' }
      return
    }
    try {
      const res = await checkConflict({
        classroomId: pickedRoom.value.id,
        date: reserveForm.reserveDate,
        startTime: reserveForm.startTime,
        endTime: reserveForm.endTime
      })
      conflictInfo.value = res.data || null
    } catch {
      conflictInfo.value = null
    }
  }
)

async function handleSubmit() {
  if (!pickedRoom.value) {
    return
  }
  // 表单校验失败时静默返回（与 ClassroomDetail.handleSubmitReserve 写法对齐，避免未处理 Promise 拒绝）
  try {
    await reserveFormRef.value.validate()
  } catch {
    return
  }
  // N3：预约校验统一为公共纯函数（含 8h 上限，此前该入口缺失；AI 解析值可能超窗，提交前兜底）
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

/** 关闭弹窗后重置全部状态 */
function resetAll() {
  step.value = 1
  resetParse()
  rooms.value = []
  pickedRoom.value = null
  conflictInfo.value = null
  Object.assign(reserveForm, { reserveDate: '', startTime: '', endTime: '', purpose: '' })
}
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
