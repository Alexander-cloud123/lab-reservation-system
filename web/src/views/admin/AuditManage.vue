<template>
  <div class="audit-page page-container">
    <!-- 页头：标题 + 说明 + 右侧提示 -->
    <div class="page-head">
      <div>
        <h1 class="page-title">预约审核</h1>
        <p class="page-sub">处理待审核预约申请，支持单条与批量审核</p>
      </div>
      <p class="page-tip">AI 合规校验仅作提示，不改动预约状态</p>
    </div>

    <!-- 筛选条：一张白面，控件同高 -->
    <div class="search-card">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="待审核" :value="0" />
            <el-option label="已通过" :value="1" />
            <el-option label="已驳回" :value="2" />
            <el-option label="已取消" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期范围">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            style="width: 260px"
          />
        </el-form-item>
        <el-form-item label="关键词">
          <el-input
            v-model="query.keyword"
            placeholder="用户账号 / 姓名 / 教室名称"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 审核表格：白面表格容器 -->
    <div class="table-band">
      <div class="section-title">
        <span class="title-left">
          预约记录
          <span class="section-note">共 {{ total }} 条，已选 {{ selectedIds.length }} 条</span>
        </span>
        <span class="band-actions">
          <el-button
            type="primary"
            :icon="CircleCheck"
            :disabled="!selectedIds.length"
            @click="handleBatchAudit(1)"
          >
            批量通过（{{ selectedIds.length }}）
          </el-button>
          <el-button
            type="danger"
            :icon="CircleClose"
            :disabled="!selectedIds.length"
            @click="openBatchReject"
          >
            批量驳回
          </el-button>
        </span>
      </div>

      <el-table v-loading="loading" :data="records" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="48" />
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column label="预约人" width="120">
          <template #default="{ row }">
            <div class="user-cell">
              <span>{{ row.userName }}</span>
              <span class="user-account">{{ row.userAccount }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="教室" min-width="160">
          <template #default="{ row }">
            <div class="room-cell">
              <span class="room-name">{{ row.classroomName }}</span>
              <span class="room-no">{{ row.building }}-{{ row.roomNo }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="reserveDate" label="预约日期" width="112" />
        <el-table-column label="时段" width="120">
          <template #default="{ row }">{{ row.startTime }}-{{ row.endTime }}</template>
        </el-table-column>
        <!-- 用途 + AI 合规校验标签（R7，需求文档 2.4 审核页「AI 校验」：违规红色高亮+悬浮原因，只提示不改状态） -->
        <el-table-column label="用途" min-width="200">
          <template #default="{ row }">
            <div class="purpose-cell">
              <el-tooltip v-if="complianceInfo(row)" :content="complianceTip(row)" placement="top">
                <span :class="{ 'purpose-violation': isViolation(row) }">{{ row.purpose }}</span>
              </el-tooltip>
              <span v-else>{{ row.purpose }}</span>
              <el-tag
                v-if="row.status === 0 && aiEnabled && complianceInfo(row)"
                :type="isViolation(row) ? 'danger' : 'success'"
                effect="plain"
                class="ai-tag"
              >
                {{ complianceSourceText(row) }}{{ isViolation(row) ? '：违规' : '：通过' }}
              </el-tag>
              <el-tag
                v-else-if="row.status === 0 && aiEnabled && !complianceInfo(row)"
                type="info"
                effect="plain"
                class="ai-tag ai-tag-clickable"
                v-clickable
                @click="checkSingle(row)"
              >
                {{ isChecking(row) ? '校验中…' : 'AI 校验' }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="88">
          <template #default="{ row }">
            <span class="status-cell" :class="statusCellClass(row.status)">{{ statusText(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="auditRemark" label="审核备注" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.auditRemark || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 0">
              <el-button link type="primary" @click="handleAudit(row, 1)">通过</el-button>
              <el-button link type="danger" @click="openReject(row)">驳回</el-button>
            </template>
            <span v-else class="no-action">已处理</span>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-slate">
            暂无符合条件的预约记录<span class="empty-action" @click="handleReset">重置筛选</span>
          </div>
        </template>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="loadData"
          @current-change="loadData"
        />
      </div>
    </div>

    <!-- 驳回弹窗（预置快捷原因，单条/批量共用） -->
    <el-dialog
      v-model="rejectVisible"
      :title="rejectMode === 'single' ? '驳回预约' : '批量驳回预约'"
      width="460px"
      :close-on-click-modal="false"
    >
      <el-form label-width="88px">
        <el-form-item label="快捷原因">
          <div class="quick-reasons">
            <el-tag
              v-for="reason in quickReasons"
              :key="reason"
              :type="rejectForm.auditRemark === reason ? 'primary' : 'info'"
              class="reason-tag"
              v-clickable
              @click="rejectForm.auditRemark = reason"
            >
              {{ reason }}
            </el-tag>
          </div>
        </el-form-item>
        <el-form-item label="审核备注">
          <el-input
            v-model="rejectForm.auditRemark"
            type="textarea"
            :rows="3"
            maxlength="255"
            placeholder="驳回必须填写审核备注"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button
          type="danger"
          :loading="submitting"
          :disabled="!rejectForm.auditRemark.trim()"
          @click="confirmReject"
        >
          确认驳回
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, CircleCheck, CircleClose } from '@element-plus/icons-vue'
import { pageManageReservations, auditReservation, batchAuditReservations } from '@/api/reservation'
import { aiComplianceCheckStream } from '@/api/ai'
import { useUserStore } from '@/stores/user'
import { statusText, statusCellClass } from '@/utils/dict'

const loading = ref(false)
const records = ref([])
const total = ref(0)
const selectedIds = ref([])
const dateRange = ref(null)

/* ===== AI 合规校验（R7：待审核记录「AI 校验」标签，违规红色高亮+悬浮原因，只提示不改状态）===== */
/**
 * AI 是否启用（控制「AI 校验」标签显隐）
 * R8 口径：登录接口已随 LoginVO 下发全局开关并落盘（store.aiEnabled）——false 表示后端明确关闭，
 * 直接不渲染入口；true / null(未知) 时先渲染入口，由校验结果校正（接口返回 enabled=false 即隐藏）。
 * 此前该值初值为 false、只能由「校验成功」置 true，而点击入口又要求它已为 true：
 * 首屏限量自动校验一旦全部超时/限流，入口会永久不渲染，用户无法重试（已修复）。
 */
const userStore = useUserStore()
const aiEnabled = ref(userStore.aiEnabled !== false)
/** 校验结果缓存：key=预约 ID → { compliant, reason }（Map 保证按行读取，不写库） */
const complianceMap = ref(new Map())
/** 进行中校验的预约 ID 集合（M9：行级 loading，防重复点击重复请求） */
const checkingIds = ref(new Set())
/** 页面加载时自动校验条数上限（M9 修复：此前对整页全部待审核记录并发发起请求，页长 50 时必然触发后端限流，多数请求失败且界面长期停在"校验中"） */
const AUTO_CHECK_LIMIT = 5
/** 自动校验并发上限（M9：小并发，避免一次性耗尽后端 RPM 额度） */
const AUTO_CHECK_CONCURRENCY = 2
/** 每次翻页/刷新/卸载都会作废旧流并中止连接，防止上一页结果写回新页。 */
let complianceGeneration = 0
let listSeq = 0
const complianceControllers = new Map()

function cancelComplianceChecks() {
  complianceGeneration += 1
  complianceControllers.forEach((controller) => controller.abort())
  complianceControllers.clear()
  checkingIds.value.clear()
}

/** 获取某条记录的 AI 校验结果 */
function complianceInfo(row) {
  return complianceMap.value.get(row.id) || null
}

/** 该行是否正在校验（M9：行级 loading 态） */
function isChecking(row) {
  return checkingIds.value.has(row.id)
}

/** 是否违规（用途红色高亮） */
function isViolation(row) {
  const info = complianceInfo(row)
  return !!(info && info.compliant === false)
}

/**
 * 校验来源标签：降级时判定来自本地关键词规则，标注「AI 校验」不准确（AGENTS 4.4 标注口径）
 */
function complianceSourceText(row) {
  const info = complianceInfo(row)
  return info && info.degraded ? '规则校验' : 'AI 校验'
}

/** 悬浮文案：降级时补充原因，使管理员能看到「模型判定」与「关键词规则判定」的区别 */
function complianceTip(row) {
  const info = complianceInfo(row)
  if (!info) {
    return ''
  }
  return info.degraded ? `${info.reason}（${info.notice}）` : info.reason
}

/**
 * 单行按需 AI 合规校验（M9：点击行内「AI 校验」标签触发；结果缓存，已校验/校验中的行不重复请求；
 * 失败静默，标签保持可点击重试，不阻断列表加载与人工审核）
 * R13-5：改走 SSE 结构化流式（meta→phase→result→done），对外保持 Promise 契约，
 * 供 autoComplianceCheck 的并发队列 await；行级 loading 已覆盖 phase 等待，无需额外进度 UI。
 */
function checkSingle(row) {
  if (!row || row.status !== 0 || isChecking(row) || complianceInfo(row)) {
    return Promise.resolve()
  }
  const generation = complianceGeneration
  const controller = new AbortController()
  checkingIds.value.add(row.id)
  complianceControllers.set(row.id, controller)
  let pendingResult = null
  let finished = false
  const finish = () => {
    if (finished) return
    finished = true
    if (generation === complianceGeneration) checkingIds.value.delete(row.id)
    if (complianceControllers.get(row.id) === controller) complianceControllers.delete(row.id)
  }

  const stream = aiComplianceCheckStream(
    { purpose: row.purpose },
    {
      onResult: (data) => {
        if (generation === complianceGeneration) pendingResult = data
      },
      onDone: () => {
        // result 后收到 done 才提交到页面，异常 EOF/超时不会留下看似成功的校验结果。
        if (generation !== complianceGeneration || !pendingResult) {
          finish()
          return
        }
        const data = pendingResult
        if (data.enabled === false) {
          aiEnabled.value = false
        } else {
          aiEnabled.value = true
          complianceMap.value.set(row.id, {
            compliant: data.compliant,
            reason: data.reason || '合规校验完成',
            degraded: !!data.message,
            notice: data.message || ''
          })
        }
        finish()
      },
      // 校验失败静默，标签保持「AI 校验」可点击重试；主动 abort 由返回 Promise 的 finally 收尾。
      onError: finish
    },
    controller.signal
  )
  return stream.finally(finish)
}

/**
 * 页面加载后对当前页前 N 条待审核记录限量自动校验（M9：小并发串行队列，
 * 保留 R7「AI 校验」亮点展示，又不触发自身限流；其余行点击标签按需校验）
 */
async function autoComplianceCheck(list) {
  const generation = complianceGeneration
  const pending = list.filter((r) => r.status === 0).slice(0, AUTO_CHECK_LIMIT)
  if (!pending.length) {
    return
  }
  let idx = 0
  const workers = Array.from(
    { length: Math.min(AUTO_CHECK_CONCURRENCY, pending.length) },
    async () => {
      while (generation === complianceGeneration && idx < pending.length) {
        const r = pending[idx++]
        await checkSingle(r)
      }
    }
  )
  await Promise.all(workers)
}

/** 查询条件（含分页） */
const query = reactive({
  page: 1,
  size: 10,
  status: undefined,
  keyword: ''
})

/** 驳回弹窗状态（单条/批量共用） */
const rejectVisible = ref(false)
const rejectMode = ref('single')
const rejectTarget = ref(null)
const submitting = ref(false)
const rejectForm = reactive({ auditRemark: '' })

/** 快捷驳回原因（需求文档 1.3 管理员端：预置常用驳回原因） */
const quickReasons = ['时间冲突', '用途不明确', '教室维护', '人数超限']

/** 加载预约列表 */
async function loadData() {
  const seq = ++listSeq
  cancelComplianceChecks()
  loading.value = true
  try {
    const params = {
      page: query.page,
      size: query.size,
      status:
        query.status === null || query.status === undefined || query.status === ''
          ? undefined
          : query.status,
      keyword: query.keyword || undefined
    }
    if (dateRange.value && dateRange.value.length === 2) {
      params.startDate = dateRange.value[0]
      params.endDate = dateRange.value[1]
    }
    const res = await pageManageReservations(params)
    if (seq !== listSeq) return
    records.value = res.data.records
    total.value = res.data.total
    // M9：翻页/刷新时清空校验缓存与进行中标记（防 complianceMap 只增不减的持续累积）
    complianceMap.value.clear()
    checkingIds.value.clear()
    // 当前页前 N 条待审核记录限量自动 AI 合规校验（只读，不阻断）
    autoComplianceCheck(records.value)
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    if (seq === listSeq) loading.value = false
  }
}

/** 查询 / 重置 */
function handleSearch() {
  query.page = 1
  loadData()
}

function handleReset() {
  query.status = undefined
  query.keyword = ''
  dateRange.value = null
  query.page = 1
  loadData()
}

/** 表格多选 */
function handleSelectionChange(rows) {
  selectedIds.value = rows.map((r) => r.id)
}

/** 单条审核（通过）——二次确认 */
async function handleAudit(row, status) {
  const action = status === 1 ? '通过' : '驳回'
  try {
    await ElMessageBox.confirm(
      `确定要${action}「${row.userName}」预约 ${row.classroomName} ${row.reserveDate} ${row.startTime}-${row.endTime} 吗？`,
      `${action}确认`,
      { confirmButtonText: `确定${action}`, cancelButtonText: '取消', type: 'warning' }
    )
    if (status === 1) {
      await auditReservation(row.id, { status: 1 })
      ElMessage.success('审核通过')
    }
    loadData()
  } catch {
    // 用户取消或接口报错（统一提示）
  }
}

/** 单条驳回：打开驳回弹窗 */
function openReject(row) {
  rejectMode.value = 'single'
  rejectTarget.value = row
  rejectForm.auditRemark = ''
  rejectVisible.value = true
}

/** 批量驳回：打开驳回弹窗 */
function openBatchReject() {
  rejectMode.value = 'batch'
  rejectTarget.value = null
  rejectForm.auditRemark = ''
  rejectVisible.value = true
}

/** 确认驳回（单条/批量） */
async function confirmReject() {
  if (!rejectForm.auditRemark.trim()) {
    ElMessage.warning('请填写审核备注')
    return
  }
  const remark = rejectForm.auditRemark.trim()
  const modeText = rejectMode.value === 'single' ? '驳回' : `批量驳回 ${selectedIds.value.length} 条`
  try {
    await ElMessageBox.confirm(`确定要${modeText}吗？驳回后不可修改。`, '驳回确认', {
      confirmButtonText: '确定驳回',
      cancelButtonText: '取消',
      type: 'warning'
    })
    submitting.value = true
    if (rejectMode.value === 'single') {
      await auditReservation(rejectTarget.value.id, { status: 2, auditRemark: remark })
      ElMessage.success('已驳回')
    } else {
      const res = await batchAuditReservations({
        ids: selectedIds.value,
        status: 2,
        auditRemark: remark
      })
      ElMessage.success(`批量驳回成功，共 ${res.data} 条`)
    }
    rejectVisible.value = false
    loadData()
  } catch {
    // 用户取消或接口报错（统一提示）
  } finally {
    submitting.value = false
  }
}

/** 批量通过（仅待审核可参与，后端兜底）——二次确认 */
async function handleBatchAudit(status) {
  const action = status === 1 ? '通过' : '驳回'
  try {
    await ElMessageBox.confirm(
      `确定要批量${action}选中的 ${selectedIds.value.length} 条预约吗？仅待审核记录会生效。`,
      `批量${action}确认`,
      { confirmButtonText: `确定${action}`, cancelButtonText: '取消', type: 'warning' }
    )
    submitting.value = true
    const res = await batchAuditReservations({ ids: selectedIds.value, status: 1 })
    ElMessage.success(`批量通过成功，共 ${res.data} 条`)
    loadData()
  } catch {
    // 用户取消或接口报错（统一提示）
  } finally {
    submitting.value = false
  }
}

onMounted(loadData)
onBeforeUnmount(() => {
  listSeq += 1
  cancelComplianceChecks()
})
</script>

<style scoped>
/* 筛选条：白面 + 24px 内边距，标签与控件同基线 */
.search-card {
  margin-bottom: var(--space-xl);
  padding: var(--space-lg) var(--space-xl);
}
.search-card :deep(.el-form-item) {
  margin-bottom: 0;
  margin-right: var(--space-xl);
}
.search-card :deep(.el-form-item__label) {
  font-size: var(--text-sm);
  color: var(--ink-soft);
  padding-right: var(--space-sm);
}

/* 表格容器：白面 + 24px 内边距，标题行右侧放批量操作 */
.table-band {
  padding: var(--space-xl);
}
.title-left .section-note {
  margin-left: var(--space-sm);
  font-weight: 400;
}
.band-actions :deep(.el-button) {
  margin-left: var(--space-sm);
}

/* 双行单元格：主信息 + 次要信息，靠字号与颜色分层 */
.user-cell,
.room-cell {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}
.user-account,
.room-no {
  font-size: var(--text-xs);
  color: var(--ink-soft);
}
.room-name {
  color: var(--ink);
  font-weight: 500;
}
.no-action {
  font-size: var(--text-sm);
  color: var(--ink-soft);
}

/* 用途 + AI 合规校验（R7）：违规红色高亮、行级标签 */
.purpose-cell {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}
.purpose-violation {
  color: var(--danger);
  font-weight: 500;
}
.ai-tag {
  flex-shrink: 0;
}
.ai-tag-clickable {
  cursor: pointer;
  transition: filter 0.15s ease;
}
.ai-tag-clickable:hover {
  filter: brightness(0.92);
}

/* 驳回弹窗快捷原因 */
.quick-reasons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}
.reason-tag {
  cursor: pointer;
}

/* 空态：覆盖组件内置空文案行高，统一走 empty-slate 原语 */
:deep(.el-table__empty-text) {
  width: 100%;
  line-height: 1.6;
}
.empty-slate {
  line-height: 1.6;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-xl);
}

@media (max-width: 900px) {
  .section-title {
    flex-wrap: wrap;
    gap: var(--space-md);
  }
}

/* ---------- 第四轮（晨光玻璃）覆盖 ---------- */
.search-card,
.table-band {
  background: var(--glass-strong);
  -webkit-backdrop-filter: blur(16px) saturate(145%);
  backdrop-filter: blur(16px) saturate(145%);
  border: 1px solid var(--glass-line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-surface), var(--shadow-raised), var(--glass-sheen);
}
.room-no {
  font-family: var(--font-num);
  letter-spacing: 0.03em;
  color: var(--brand-deep);
}
.room-name {
  font-weight: 600;
  color: var(--ink);
}
.band-actions {
  background: var(--grad-soft);
  border-radius: var(--radius-control);
}
.ai-tag,
.reason-tag {
  border-radius: var(--radius-pill);
}
</style>
