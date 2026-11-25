<template>
  <div class="classroom-detail-page page-container" v-loading="loading">
    <!-- 返回栏 -->
    <div class="detail-nav">
      <el-button text :icon="ArrowLeft" @click="goBack">返回列表</el-button>
      <span class="nav-title">教室详情</span>
    </div>

    <!-- 教室基础信息 -->
    <div class="info-card surface">
      <div class="info-head">
        <span class="code-block">{{ classroom.roomNo }}</span>
        <div class="room-name-wrap">
          <h2>{{ classroom.name }}</h2>
          <span class="room-no">{{ classroom.building }} {{ classroom.roomNo }}</span>
        </div>
        <span class="status-cell" :class="statusPillClass(classroom.statusLabel)">
          {{ classroom.statusLabel }}
        </span>
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
      <el-descriptions :column="descriptionColumns" class="info-table">
        <el-descriptions-item label="教室编号">{{ classroom.roomNo }}</el-descriptions-item>
        <el-descriptions-item label="所属楼栋">{{ classroom.building }}</el-descriptions-item>
        <el-descriptions-item label="教室类型">{{ typeText(classroom.type) }}</el-descriptions-item>
        <el-descriptions-item label="容纳人数">{{ classroom.capacity }} 人</el-descriptions-item>
        <el-descriptions-item label="设备说明">{{ classroom.equipment || '-' }}</el-descriptions-item>
        <el-descriptions-item label="备注描述">{{ classroom.description || '-' }}</el-descriptions-item>
      </el-descriptions>
    </div>

    <!-- 时段占用可视化 -->
    <div class="slots-card surface">
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

      <!-- 时段刻度尺：hover 空档即显可点提示，点一下把时段带回预约弹窗 -->
      <TimeRuler
        class="slots-ruler"
        :slots="classroom.occupiedSlots || []"
        :show-now="isViewingToday"
        clickable
        @pick="onPickSlot"
      />
      <p class="slots-hint">点击空白时段，直接带入预约</p>
      <p v-if="!(classroom.occupiedSlots || []).length" class="slots-empty">
        {{ queryDate || '今天' }} 暂无已通过的预约时段，可自由预约
      </p>

      <!-- 时段明细表 -->
      <el-table v-if="(classroom.occupiedSlots || []).length" :data="classroom.occupiedSlots">
        <el-table-column prop="startTime" label="开始时间" width="120" />
        <el-table-column prop="endTime" label="结束时间" width="120" />
        <el-table-column label="预约用途" min-width="200">
          <template #default="{ row }">{{ row.mine ? (row.purpose || '-') : '-' }}</template>
        </el-table-column>
      </el-table>
    </div>

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
      @close="invalidateConflict"
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
          v-if="conflictChecking"
          title="正在检测时段冲突…"
          type="info"
          :closable="false"
          show-icon
          class="conflict-alert"
        />
        <el-alert
          v-else-if="conflictError"
          :title="conflictError"
          type="error"
          :closable="false"
          show-icon
          class="conflict-alert"
        />
        <el-alert
          v-else-if="conflictDirty"
          title="预约信息已变化，等待最新冲突检测结果"
          type="warning"
          :closable="false"
          show-icon
          class="conflict-alert"
        />
        <el-alert
          v-else-if="conflictInfo"
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
          :disabled="conflictChecking || !!(conflictInfo && conflictInfo.conflict)"
          @click="handleSubmitReserve"
        >
          提交预约
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Calendar, Star, StarFilled } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import TimeRuler from '@/components/common/TimeRuler.vue'
import { getClassroomDetail } from '@/api/classroom'
import { checkConflict, submitReservation } from '@/api/reservation'
import { toggleFavorite, getFavoriteList } from '@/api/favorite'
import { validateBooking } from '@/utils/booking'
import { useUserStore } from '@/stores/user'
import { typeText } from '@/utils/dict'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const classroom = ref({})
const queryDate = ref(null)
/** ≤640px 改为单列描述，桌面继续保持三列 */
const descriptionColumns = ref(3)
let descriptionMedia = null

function syncDescriptionColumns(event) {
  descriptionColumns.value = event.matches ? 1 : 3
}

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
const conflictChecking = ref(false)
const conflictDirty = ref(false)
const conflictError = ref('')
let conflictSeq = 0
let activeConflict = null
let checkedSnapshot = ''
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
  } catch {
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
  } catch {
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
  } catch {
    // 统一错误提示已由 request.js 处理（含超过上限提示）
  } finally {
    favoriteLoading.value = false
  }
}

/** 查看的是否为今天：决定刻度尺是否显示「此刻」指针 */
const isViewingToday = computed(() => {
  const value = queryDate.value || dayjs().format('YYYY-MM-DD')
  return value === dayjs().format('YYYY-MM-DD')
})

/** 点击刻度尺空档：把时段回填进预约弹窗（仍需用户自己提交） */
function onPickSlot({ start, end }) {
  const draft = loadDraft() || {}
  reserveForm.reserveDate = queryDate.value || dayjs().format('YYYY-MM-DD')
  reserveForm.startTime = start
  reserveForm.endTime = end
  reserveForm.purpose = draft.purpose || ''
  reserveVisible.value = true
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
  } catch {
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
  timeError.value = ''
  invalidateConflict()
}

function conflictSnapshot() {
  if (!classroom.value.id) return ''
  return JSON.stringify({
    classroomId: classroom.value.id,
    date: reserveForm.reserveDate,
    startTime: reserveForm.startTime,
    endTime: reserveForm.endTime
  })
}

/** 任何表单失效场景都递增序号，防止旧响应覆盖新表单或关闭后的状态 */
function invalidateConflict() {
  conflictSeq += 1
  activeConflict = null
  checkedSnapshot = ''
  conflictInfo.value = null
  conflictChecking.value = false
  conflictDirty.value = false
  conflictError.value = ''
}

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

/** 实时冲突校验 + 提交时等待同一 Promise，避免检测期间表单变化绕过前端门槛 */
watch(
  () => [classroom.value.id, reserveForm.reserveDate, reserveForm.startTime, reserveForm.endTime],
  ([roomId, date, start, end]) => {
    if (!roomId || !date || !start || !end) {
      timeError.value = ''
      invalidateConflict()
      return
    }
    if (start >= end) {
      invalidateConflict()
      timeError.value = '开始时间必须早于结束时间'
      return
    }
    timeError.value = ''
    const snapshot = JSON.stringify({ classroomId: roomId, date, startTime: start, endTime: end })
    if (snapshot === checkedSnapshot && conflictInfo.value) return
    requestConflictCheck(snapshot)
  }
)

/** 等待当前表单快照的最新检测；检测失败/无结果/有冲突均禁止提交 */
async function ensureLatestConflict() {
  while (classroom.value.id) {
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

/** 提交预约（前端确认最新冲突结果，后端二次冲突检测兜底；成功后清除草稿） */
async function handleSubmitReserve() {
  if (submitting.value) return
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
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    submitting.value = false
  }
}

/** 返回上一页 */
function goBack() {
  router.back()
}

/** 实时状态标签色（R4 三态：空闲绿/使用中红/已结束灰） */
/** 教室状态 → 药丸样式（与列表页同一套状态语义） */
function statusPillClass(label) {
  return { 当前空闲: 'is-approved', 使用中: 'is-pending', 已结束: 'is-canceled', 已停用: 'is-canceled' }[label] || 'is-canceled'
}

onMounted(() => {
  descriptionMedia = window.matchMedia('(max-width: 640px)')
  syncDescriptionColumns(descriptionMedia)
  descriptionMedia.addEventListener('change', syncDescriptionColumns)
  loadDetail()
  loadFavoriteState()
})

onBeforeUnmount(() => {
  invalidateConflict()
  if (descriptionMedia) {
    descriptionMedia.removeEventListener('change', syncDescriptionColumns)
  }
})
</script>

<style scoped>
.detail-nav {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-bottom: var(--space-md);
}

.nav-title {
  color: var(--ink-soft);
  font-size: var(--text-sm);
  line-height: 1.5;
}

.info-card {
  margin-bottom: var(--space-lg);
}

.info-head {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  margin-bottom: var(--space-lg);
}

.info-head .code-block {
  flex-shrink: 0;
  height: 34px;
  padding: 0 12px;
  font-size: var(--text-md);
}

.room-name-wrap {
  flex: 1;
  min-width: 0;
}

.room-name-wrap h2 {
  margin: 0;
  color: var(--ink);
  font-size: var(--text-lg);
  font-weight: 600;
  line-height: 1.3;
}

.room-no {
  color: var(--ink-soft);
  font-size: var(--text-xs);
  line-height: 1.6;
}

.fav-btn {
  flex-shrink: 0;
}

.info-table {
  margin-top: var(--space-xs);
}

.info-table :deep(.el-descriptions__label) {
  color: var(--ink-soft);
  font-weight: 500;
}

.info-table :deep(.el-descriptions__content) {
  color: var(--ink);
}

.slots-card {
  margin-bottom: var(--space-lg);
}

.slots-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-md);
}

.slots-title {
  color: var(--ink);
  font-size: var(--text-base);
  font-weight: 600;
  line-height: 1.4;
}

.slots-ruler {
  margin-bottom: var(--space-md);
}

.slots-hint {
  margin: 0 0 var(--space-md);
  color: var(--ink-soft);
  font-size: var(--text-sm);
  line-height: 1.6;
}

.slots-empty {
  display: inline-block;
  padding: var(--space-sm) var(--space-md);
  border: 1px solid var(--line);
  border-radius: var(--radius-control);
  background: var(--page);
  color: var(--ink-soft);
  font-size: var(--text-sm);
  line-height: 1.6;
}

.action-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-xl);
}

.disabled-tip {
  margin-left: 0;
}

.conflict-alert {
  margin-top: var(--space-xs);
}

/* ---------- 第四轮（晨光玻璃）覆盖 ---------- */
.detail-nav {
  margin-bottom: var(--space-lg);
}
.detail-nav :deep(.el-button) {
  height: 34px;
  border: 1px solid var(--glass-line);
  background: var(--glass);
  -webkit-backdrop-filter: blur(10px);
  backdrop-filter: blur(10px);
  color: var(--ink-soft);
}
.info-card,
.slots-card {
  background: var(--glass);
  -webkit-backdrop-filter: blur(14px) saturate(140%);
  backdrop-filter: blur(14px) saturate(140%);
  border: 1px solid var(--glass-line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-surface), var(--shadow-raised), var(--glass-sheen);
}
.info-head .code-block {
  background: var(--grad);
  border-color: transparent;
  color: #fff;
  box-shadow: 0 4px 14px rgba(29, 111, 224, 0.3);
}
.room-name-wrap .room-name {
  font-size: var(--text-lg);
  letter-spacing: -0.01em;
}
.info-table :deep(.el-descriptions__label) {
  color: var(--ink-soft);
  font-size: var(--text-sm);
}
.info-table :deep(.el-descriptions__content) {
  color: var(--ink);
  font-size: var(--text-sm);
}
.slots-title {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--ink);
}
.slots-empty {
  padding: 28px 0;
  color: var(--ink-soft);
  font-size: var(--text-sm);
}
.conflict-alert {
  border-radius: var(--radius-md);
  background: var(--warning-soft);
}
.action-bar {
  position: sticky;
  bottom: 0;
  margin-top: var(--space-xl);
  padding: var(--space-lg) var(--space-xl);
  border-radius: var(--radius-card);
  background: var(--glass-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  border: 1px solid var(--glass-line);
  box-shadow: var(--shadow-float), var(--glass-sheen);
}
.fav-btn.is-favorited {
  color: var(--sun);
}
</style>