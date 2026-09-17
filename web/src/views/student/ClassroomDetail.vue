<template>
  <div class="classroom-detail-page page-container" v-loading="loading">
    <!-- 返回栏 -->
    <div class="detail-nav">
      <el-button text :icon="ArrowLeft" @click="goBack">返回列表</el-button>
      <span class="nav-title">教室详情</span>
    </div>

    <!-- 教室基础信息 -->
    <el-card shadow="never" class="info-card">
      <div class="info-head">
        <div class="room-type-icon" :class="`type-${classroom.type}`">
          <el-icon :size="24"><component :is="typeIcon(classroom.type)" /></el-icon>
        </div>
        <div class="room-name-wrap">
          <h2>{{ classroom.name }}</h2>
          <span class="room-no">{{ classroom.building }} · {{ classroom.roomNo }}</span>
        </div>
        <el-tag :type="statusTagType(classroom.statusLabel)" effect="light" round size="small">
          {{ classroom.statusLabel }}
        </el-tag>
        <el-button
          class="fav-btn"
          :type="favorited ? 'warning' : 'default'"
          :icon="favorited ? StarFilled : Star"
          :loading="favoriteLoading"
          :disabled="favoriteLoading"
          @click="handleToggleFavorite"
        >
          {{ favorited ? '已收藏' : '收藏' }}
        </el-button>
      </div>
      <el-descriptions :column="3" border class="info-table">
        <el-descriptions-item label="教室编号">{{ classroom.roomNo }}</el-descriptions-item>
        <el-descriptions-item label="所属楼栋">{{ classroom.building }}</el-descriptions-item>
        <el-descriptions-item label="教室类型">{{ typeText(classroom.type) }}</el-descriptions-item>
        <el-descriptions-item label="容纳人数">{{ classroom.capacity }} 人</el-descriptions-item>
        <el-descriptions-item label="设备说明">{{ classroom.equipment || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注描述">{{ classroom.description || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 时段占用可视化 -->
    <el-card shadow="never" class="slots-card">
      <div class="slots-head">
        <span class="slots-title">时段占用（已通过预约）</span>
        <el-date-picker
          v-model="queryDate"
          type="date"
          placeholder="选择日期查看"
          value-format="YYYY-MM-DD"
          clearable
          style="width: 180px"
          @change="loadDetail"
        />
      </div>

      <!-- 横向时间轴可视化（08:00-22:00） -->
      <div class="timeline">
        <div class="timeline-axis">
          <span v-for="h in axisHours" :key="h" class="axis-hour" :style="{ left: axisLeft(h) + '%' }">
            {{ h }}:00
          </span>
        </div>
        <div class="timeline-bar">
          <div
            v-for="(slot, idx) in classroom.occupiedSlots || []"
            :key="idx"
            class="timeline-slot"
            :style="slotStyle(slot.startTime, slot.endTime)"
            :title="slot.mine && slot.purpose ? `${slot.startTime}-${slot.endTime} ${slot.purpose}` : `${slot.startTime}-${slot.endTime}`"
          >
            {{ slot.startTime }}-{{ slot.endTime }}
          </div>
        </div>
        <p v-if="!(classroom.occupiedSlots || []).length" class="slots-empty">
          {{ queryDate || '今天' }} 暂无已通过的预约时段，可自由预约
        </p>
      </div>

      <!-- 时段明细表 -->
      <el-table v-if="(classroom.occupiedSlots || []).length" :data="classroom.occupiedSlots" size="small" stripe>
        <el-table-column prop="startTime" label="开始时间" width="120" />
        <el-table-column prop="endTime" label="结束时间" width="120" />
        <el-table-column label="预约用途" min-width="200">
          <template #default="{ row }">{{ row.mine ? (row.purpose || '-') : '-' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <div class="action-bar">
      <el-button
        type="primary"
        size="large"
        :icon="Calendar"
        :disabled="classroom.status === 0"
        :title="classroom.status === 0 ? '该教室已停用，暂不可预约' : ''"
        @click="openReserveDialog"
      >
        预约申请
      </el-button>
      <el-text v-if="classroom.status === 0" type="info" size="small" class="disabled-tip">
        该教室已停用，暂不可预约
      </el-text>
    </div>

    <!-- 预约申请弹窗 -->
    <el-dialog
      v-model="reserveVisible"
      title="预约申请"
      width="480px"
      :close-on-click-modal="false"
      @closed="handleDialogClosed"
    >
      <el-form ref="reserveFormRef" :model="reserveForm" :rules="reserveRules" label-width="90px">
        <el-form-item label="教室">
          <span>{{ classroom.name }}（{{ classroom.roomNo }}）</span>
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

        <!-- 实时冲突校验提示 -->
        <el-alert
          v-if="timeError"
          :title="timeError"
          type="error"
          :closable="false"
          show-icon
          class="conflict-alert"
        />
        <el-alert
          v-if="conflictInfo"
          :type="conflictInfo.conflict ? 'error' : 'success'"
          :title="conflictInfo.reason"
          :closable="false"
          show-icon
          class="conflict-alert"
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
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Calendar, Notebook, Cpu, School, Star, StarFilled } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { getClassroomDetail } from '@/api/classroom'
import { checkConflict, submitReservation } from '@/api/reservation'
import { toggleFavorite, getFavoriteList } from '@/api/favorite'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const classroom = ref({})
const queryDate = ref(null)

/** 收藏状态（R4：详情页收藏/取消收藏按钮） */
const favorited = ref(false)
/** 收藏按钮请求中标记（R6：防止快速连续点击导致重复 toggle 抖动） */
const favoriteLoading = ref(false)

/** 预约弹窗状态 */
const reserveVisible = ref(false)
const submitting = ref(false)
const reserveFormRef = ref(null)
const reserveForm = reactive({ reserveDate: '', startTime: '', endTime: '', purpose: '' })
const conflictInfo = ref(null)
/** 前端时间合理性校验提示（L9 优化：开始时间必须早于结束时间；后端仍强制兜底） */
const timeError = ref('')
/** 本次弹窗是否已提交成功（提交成功后关闭不再保存草稿） */
const justSubmitted = ref(false)

/** 表单校验规则 */
const reserveRules = {
  reserveDate: [{ required: true, message: '请选择预约日期', trigger: 'change' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }],
  purpose: [{ required: true, message: '请填写预约用途', trigger: 'blur' }]
}

/** 时间轴可视范围：08:00 - 22:00 */
const AXIS_START = 8
const AXIS_END = 22
const axisHours = []
for (let h = AXIS_START; h <= AXIS_END; h++) {
  axisHours.push(h)
}

/** 预约草稿 localStorage key（R4：按用户 + 教室维度存储 日期/时段/用途） */
const draftKey = computed(() => `reservation_draft_${userStore.userInfo ? userStore.userInfo.id : ''}_${route.params.id}`)

/** 加载教室详情（含指定日期占用） */
async function loadDetail() {
  loading.value = true
  try {
    const id = route.params.id
    const params = {}
    if (queryDate.value) {
      params.date = queryDate.value
    }
    const res = await getClassroomDetail(id, params)
    classroom.value = res.data
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  } finally {
    loading.value = false
  }
}

/** 加载收藏状态：当前教室是否已收藏（同步收藏按钮） */
async function loadFavoriteState() {
  try {
    const res = await getFavoriteList()
    const id = Number(route.params.id)
    favorited.value = (res.data || []).some((f) => Number(f.classroomId) === id)
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  }
}

/** 收藏/取消收藏（toggle，后端校验上限 10；请求期间禁用按钮防重复点击） */
async function handleToggleFavorite() {
  if (favoriteLoading.value) {
    return
  }
  favoriteLoading.value = true
  try {
    const res = await toggleFavorite(route.params.id)
    favorited.value = res.data
    ElMessage.success(res.message || (favorited.value ? '收藏成功' : '已取消收藏'))
  } catch (e) {
    // 统一错误提示已由 request.js 处理（含超过上限提示）
  } finally {
    favoriteLoading.value = false
  }
}

/** 时间轴刻度位置 */
function axisLeft(hour) {
  return ((hour - AXIS_START) / (AXIS_END - AXIS_START)) * 100
}

/** 占用色块位置与宽度 */
function slotStyle(start, end) {
  const startMin = timeToMin(start)
  const endMin = timeToMin(end)
  const rangeMin = (AXIS_END - AXIS_START) * 60
  const left = ((startMin - AXIS_START * 60) / rangeMin) * 100
  const width = ((endMin - startMin) / rangeMin) * 100
  return { left: `${Math.max(left, 0)}%`, width: `${Math.min(width, 100 - Math.max(left, 0))}%` }
}

/** HH:mm → 分钟数 */
function timeToMin(time) {
  const [h, m] = time.split(':').map(Number)
  return h * 60 + m
}

/** 打开预约弹窗：优先回填已保存草稿，无草稿默认今天 */
function openReserveDialog() {
  const draft = loadDraft()
  if (draft && draft.reserveDate) {
    reserveForm.reserveDate = draft.reserveDate
    reserveForm.startTime = draft.startTime || ''
    reserveForm.endTime = draft.endTime || ''
    reserveForm.purpose = draft.purpose || ''
  } else {
    reserveForm.reserveDate = dayjs().format('YYYY-MM-DD')
    reserveForm.startTime = ''
    reserveForm.endTime = ''
    reserveForm.purpose = ''
  }
  justSubmitted.value = false
  reserveVisible.value = true
}

/** 读取预约草稿 */
function loadDraft() {
  try {
    return JSON.parse(localStorage.getItem(draftKey.value) || 'null')
  } catch (e) {
    return null
  }
}

/** 保存预约草稿（未提交关闭时自动保存） */
function saveDraft() {
  if (!reserveForm.reserveDate && !reserveForm.startTime && !reserveForm.endTime && !reserveForm.purpose) {
    return
  }
  localStorage.setItem(
    draftKey.value,
    JSON.stringify({
      reserveDate: reserveForm.reserveDate,
      startTime: reserveForm.startTime,
      endTime: reserveForm.endTime,
      purpose: reserveForm.purpose
    })
  )
}

/** 清除预约草稿（提交成功后） */
function clearDraft() {
  localStorage.removeItem(draftKey.value)
}

/** 弹窗关闭：未提交则自动保存草稿，提交成功则清除草稿 */
function handleDialogClosed() {
  if (justSubmitted.value) {
    clearDraft()
    justSubmitted.value = false
  } else {
    saveDraft()
  }
  resetReserveForm()
}

/** 重置预约表单 */
function resetReserveForm() {
  reserveFormRef.value && reserveFormRef.value.resetFields()
  reserveForm.reserveDate = ''
  reserveForm.startTime = ''
  reserveForm.endTime = ''
  reserveForm.purpose = ''
  conflictInfo.value = null
}

/**
 * 实时冲突校验（R3 前端双重校验第一层）：
 * 日期与时段齐全时调用冲突检测接口，冲突则禁用提交并提示
 * 竞态保护：每次发请求前自增序号，仅采纳序号最新一次的响应（快速调整时段时旧响应不覆盖新结果）
 */
let conflictSeq = 0
watch(
  () => [reserveForm.reserveDate, reserveForm.startTime, reserveForm.endTime],
  async ([date, start, end]) => {
    if (!date || !start || !end) {
      conflictInfo.value = null
      timeError.value = ''
      return
    }
    // 前端时间合理性校验：开始时间必须早于结束时间（L9 优化，后端仍强制兜底）
    if (start >= end) {
      conflictInfo.value = null
      timeError.value = '开始时间必须早于结束时间'
      return
    }
    timeError.value = ''
    const seq = ++conflictSeq
    try {
      const res = await checkConflict({
        classroomId: classroom.value.id,
        date,
        startTime: start,
        endTime: end
      })
      if (seq !== conflictSeq) {
        // 已有更新的请求发出，丢弃本次过期响应
        return
      }
      conflictInfo.value = res.data
    } catch (e) {
      if (seq !== conflictSeq) {
        return
      }
      conflictInfo.value = null
    }
  }
)

/** 提交预约（后端二次冲突检测兜底；成功后清除草稿） */
async function handleSubmitReserve() {
  try {
    await reserveFormRef.value.validate()
  } catch (e) {
    return
  }
  // 前端时间合理性校验（L9 优化，后端仍强制兜底）
  if (reserveForm.startTime && reserveForm.endTime && reserveForm.startTime >= reserveForm.endTime) {
    ElMessage.warning('开始时间必须早于结束时间')
    return
  }
  // H3 前端配合：单次时长上限（与后端 Constants.MAX_RESERVATION_HOURS=8 双重校验口径一致，超长在提交前即提示）
  if (reserveForm.startTime && reserveForm.endTime) {
    const [sh, sm] = reserveForm.startTime.split(':').map(Number)
    const [eh, em] = reserveForm.endTime.split(':').map(Number)
    if (eh + em / 60 - (sh + sm / 60) > 8) {
      ElMessage.warning('单次预约时长不能超过 8 小时')
      return
    }
  }
  submitting.value = true
  try {
    const res = await submitReservation({
      classroomId: classroom.value.id,
      reserveDate: reserveForm.reserveDate,
      startTime: reserveForm.startTime,
      endTime: reserveForm.endTime,
      purpose: reserveForm.purpose
    })
    ElMessage.success(res.message || '预约提交成功，待管理员审核')
    justSubmitted.value = true
    reserveVisible.value = false
    loadDetail()
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  } finally {
    submitting.value = false
  }
}

/** 返回上一页 */
function goBack() {
  router.back()
}

/** 类型文案 */
function typeText(type) {
  return { 1: '普通教室', 2: '实验室', 3: '机房' }[type] || '未知'
}

/** 类型图标 */
function typeIcon(type) {
  return { 1: School, 2: Cpu, 3: Notebook }[type] || School
}

/** 实时状态标签色（R4 三态：空闲绿/使用中红/已结束灰） */
function statusTagType(label) {
  return { 当前空闲: 'success', 使用中: 'danger', 已结束: 'info' }[label] || 'info'
}

onMounted(() => {
  loadDetail()
  loadFavoriteState()
})
</script>

<style scoped>
.detail-nav {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}
.nav-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-regular);
}

.info-card {
  margin-bottom: 16px;
  border-radius: var(--radius-lg);
}

.info-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 16px;
}

.room-type-icon {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.room-type-icon.type-1 {
  background: var(--brand-primary-light);
  color: var(--brand-primary);
}
.room-type-icon.type-2 {
  background: var(--brand-success-light);
  color: var(--brand-success);
}
.room-type-icon.type-3 {
  background: var(--brand-warning-light);
  color: var(--brand-warning);
}

.room-name-wrap {
  flex: 1;
  min-width: 0;
}
.room-name-wrap h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: 20px;
  font-weight: 700;
}
.room-no {
  font-size: 12px;
  color: var(--text-placeholder);
}

.fav-btn {
  flex-shrink: 0;
}

.info-table {
  margin-top: 4px;
}
.info-table :deep(.el-descriptions__label) {
  font-weight: 600;
}

.slots-card {
  margin-bottom: 16px;
  border-radius: var(--radius-lg);
}

.slots-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.slots-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.timeline {
  position: relative;
  padding: 8px 0 4px;
  margin-bottom: 14px;
}

.timeline-axis {
  position: relative;
  height: 18px;
}

.axis-hour {
  position: absolute;
  transform: translateX(-50%);
  font-size: 11px;
  color: var(--text-placeholder);
}

.timeline-bar {
  position: relative;
  height: 32px;
  background: var(--brand-info-light);
  border-radius: 8px;
  overflow: hidden;
}

.timeline-slot {
  position: absolute;
  top: 0;
  bottom: 0;
  background: var(--brand-danger);
  color: #fff;
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  overflow: hidden;
  white-space: nowrap;
}

.slots-empty {
  color: var(--text-secondary);
  font-size: 13px;
  background: var(--brand-success-light);
  border: 1px dashed var(--el-color-success-light-7);
  border-radius: 10px;
  padding: 10px 14px;
  display: inline-block;
}

.action-bar {
  text-align: center;
  margin-bottom: 24px;
}

.disabled-tip {
  margin-left: 12px;
}

.conflict-alert {
  margin-top: 4px;
}
</style>
