<template>
  <div class="profile-page page-container">
    <!-- 页面标题栏：时间感知问候（2025 趋势：个性化首屏） -->
    <div class="page-head">
      <div>
        <div class="page-title">{{ greeting }}，{{ displayName }}</div>
        <div class="page-sub">管理身份资料、预约数据与账户安全</div>
      </div>
    </div>

    <!-- ① 身份卡 + ② 数据概览（个人名片：头像/姓名/学号 + 4 项预约统计） -->
    <section class="identity-card">
      <div class="identity-row">
        <div class="identity-avatar-wrap">
          <el-avatar :size="56" class="identity-avatar">{{ avatarText }}</el-avatar>
        </div>
        <div class="identity-main">
          <div class="identity-name-line">
            <span class="identity-name">{{ displayName }}</span>
            <el-tag size="small" effect="plain" class="identity-role">学生</el-tag>
          </div>
          <div class="identity-meta">学号 {{ studentNoText }} · 账号 {{ usernameText }}</div>
        </div>
        <el-button text type="primary" class="identity-edit" @click="openEditDialog">
          <el-icon><EditPen /></el-icon>
          编辑资料
        </el-button>
      </div>
      <div class="identity-divider" />
      <div class="overview-row">
        <div class="overview-item">
          <div class="overview-value">{{ stats.totalReservations ?? 0 }}</div>
          <div class="overview-label">累计预约</div>
        </div>
        <div class="overview-item">
          <div class="overview-value">{{ stats.monthReservations ?? 0 }}</div>
          <div class="overview-label">本月预约</div>
        </div>
        <div class="overview-item">
          <div class="overview-value">{{ stats.approvalRate ?? 0 }}%</div>
          <div class="overview-label">审核通过率</div>
        </div>
        <div class="overview-item">
          <div class="overview-value overview-value-sm">{{ lastReserveText }}</div>
          <div class="overview-label">最近一次预约</div>
        </div>
      </div>
    </section>

    <!-- ③ 快捷功能宫格（信息分流：高频业务入口前置） -->
    <el-card shadow="never" class="section-card">
      <div class="section-head">
        <span class="section-title">快捷功能</span>
      </div>
      <div class="quick-grid">
        <div class="quick-item" @click="goMyReservations">
          <div class="quick-icon"><el-icon :size="20"><Tickets /></el-icon></div>
          <div class="quick-info">
            <div class="quick-name">我的预约</div>
            <div class="quick-desc">查看预约记录与审核状态</div>
          </div>
        </div>
        <div class="quick-item" @click="goCalendar">
          <div class="quick-icon"><el-icon :size="20"><Calendar /></el-icon></div>
          <div class="quick-info">
            <div class="quick-name">预约日历</div>
            <div class="quick-desc">按日历总览与发起预约</div>
          </div>
        </div>
        <div class="quick-item" @click="scrollToFav">
          <div class="quick-icon"><el-icon :size="20"><School /></el-icon></div>
          <div class="quick-info">
            <div class="quick-name">常用教室</div>
            <div class="quick-desc">一键直达已收藏教室</div>
          </div>
        </div>
        <div class="quick-item" @click="goClassroomList">
          <div class="quick-icon"><el-icon :size="20"><OfficeBuilding /></el-icon></div>
          <div class="quick-info">
            <div class="quick-name">教室列表</div>
            <div class="quick-desc">浏览全部教室资源</div>
          </div>
        </div>
      </div>
    </el-card>

    <!-- ④ 常用教室（收藏列表） -->
    <el-card shadow="never" class="section-card" id="favorite-section">
      <div class="section-head">
        <span class="section-title">常用教室</span>
        <el-button text type="primary" @click="goClassroomList">去教室列表</el-button>
      </div>
      <div v-loading="favLoading" class="fav-grid">
        <div
          v-for="fav in favorites"
          :key="fav.id"
          class="fav-item"
          @click="goDetail(fav.classroomId)"
        >
          <div class="fav-icon"><el-icon :size="18"><School /></el-icon></div>
          <div class="fav-info">
            <div class="fav-name">{{ fav.name }}</div>
            <div class="fav-meta">{{ fav.building }}-{{ fav.roomNo }} · {{ typeText(fav.type) }} · {{ fav.capacity }}人</div>
          </div>
        </div>
        <!-- 收藏空状态（友好引导） -->
        <el-empty
          v-if="!favLoading && !favorites.length"
          :image-size="72"
          description="还没有收藏的教室，收藏常用教室后可一键直达预约"
        >
          <el-button type="primary" @click="goClassroomList">去收藏教室</el-button>
        </el-empty>
      </div>
    </el-card>

    <!-- ⑤ 消息通知（图标化消息流：类型图标 + 未读标记） -->
    <el-card shadow="never" class="section-card">
      <div class="section-head">
        <span class="section-title">消息通知</span>
        <div class="section-actions">
          <el-tag v-if="unreadCount > 0" type="danger" size="small" round>{{ unreadCount }} 条未读</el-tag>
          <el-button v-if="notifications.length" text type="primary" @click="markAllRead">全部标为已读</el-button>
        </div>
      </div>
      <div v-loading="msgLoading" class="msg-list">
        <div
          v-for="msg in notifications"
          :key="msg.id"
          class="msg-item"
          :class="{ unread: !isRead(msg.id) }"
          @click="markRead(msg.id)"
        >
          <div class="msg-icon" :class="`msg-icon-${msg.type}`">
            <el-icon :size="17"><component :is="msgIcon(msg.type)" /></el-icon>
          </div>
          <div class="msg-body">
            <div class="msg-title">{{ msg.title }}</div>
            <div class="msg-content">{{ msg.content }}</div>
            <div class="msg-time">{{ formatDateTime(msg.time) }}</div>
          </div>
          <span v-if="!isRead(msg.id)" class="msg-dot" />
        </div>
        <el-empty
          v-if="!msgLoading && !notifications.length"
          :image-size="72"
          description="暂无消息通知，去预约一间教室吧"
        >
          <el-button type="primary" @click="goClassroomList">去教室列表</el-button>
        </el-empty>
      </div>
    </el-card>

    <!-- ⑥ 账户与安全（现代化分组设置列表：行点击弹窗编辑，类主流 App 设置形态） -->
    <el-card shadow="never" class="section-card account-card" id="account-security">
      <div class="section-head">
        <span class="section-title">账户与安全</span>
        <span class="section-hint">修改密码后需重新登录</span>
      </div>

      <!-- 分组一：个人资料 -->
      <div class="settings-group">
        <div class="settings-group-title">个人资料</div>
        <div class="settings-list">
          <div class="settings-row readonly">
            <div class="settings-icon"><el-icon :size="17"><Key /></el-icon></div>
            <div class="settings-label">登录账号</div>
            <div class="settings-value muted">{{ usernameText }}</div>
          </div>
          <div class="settings-row readonly">
            <div class="settings-icon"><el-icon :size="17"><Postcard /></el-icon></div>
            <div class="settings-label">学号</div>
            <div class="settings-value muted">{{ studentNoText }}</div>
          </div>
          <div class="settings-row clickable" @click="openEditDialog">
            <div class="settings-icon"><el-icon :size="17"><User /></el-icon></div>
            <div class="settings-label">姓名</div>
            <div class="settings-value">{{ displayName }}</div>
            <el-icon class="settings-chevron"><ArrowRight /></el-icon>
          </div>
          <div class="settings-row clickable" @click="openEditDialog">
            <div class="settings-icon"><el-icon :size="17"><Message /></el-icon></div>
            <div class="settings-label">邮箱</div>
            <div class="settings-value" :class="{ muted: !emailText }">{{ emailText || '未填写' }}</div>
            <el-icon class="settings-chevron"><ArrowRight /></el-icon>
          </div>
          <div class="settings-row clickable" @click="openEditDialog">
            <div class="settings-icon"><el-icon :size="17"><Iphone /></el-icon></div>
            <div class="settings-label">手机号</div>
            <div class="settings-value" :class="{ muted: !phoneText }">{{ phoneText || '未填写' }}</div>
            <el-icon class="settings-chevron"><ArrowRight /></el-icon>
          </div>
        </div>
      </div>

      <!-- 分组二：账号安全 -->
      <div class="settings-group">
        <div class="settings-group-title">账号安全</div>
        <div class="settings-list">
          <div class="settings-row clickable" @click="openPwdDialog">
            <div class="settings-icon"><el-icon :size="17"><Lock /></el-icon></div>
            <div class="settings-label">修改密码</div>
            <div class="settings-value muted">定期修改密码，保护账号安全</div>
            <el-icon class="settings-chevron"><ArrowRight /></el-icon>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 编辑资料弹窗（一步直达，减少操作层级） -->
    <el-dialog
      v-model="editDialogVisible"
      title="编辑资料"
      width="min(420px, calc(100vw - 32px))"
      @opened="clearInfoValidate"
    >
      <el-form ref="infoFormRef" :model="infoForm" :rules="infoRules" label-width="76px">
        <el-form-item label="姓名" prop="name">
          <el-input v-model="infoForm.name" maxlength="20" placeholder="请输入真实姓名" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="infoForm.email" maxlength="50" placeholder="请输入邮箱（选填）" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="infoForm.phone" maxlength="11" placeholder="请输入 11 位手机号（选填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="infoSaving" @click="handleSaveInfo">保存</el-button>
      </template>
    </el-dialog>

    <!-- 修改密码弹窗（保留校验、二次确认、重新登录） -->
    <el-dialog
      v-model="pwdDialogVisible"
      title="修改密码"
      width="min(420px, calc(100vw - 32px))"
      @opened="clearPwdValidate"
    >
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="76px">
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入原密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="不少于 6 位" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdDialogVisible = false">取消</el-button>
        <el-button type="warning" :loading="pwdSaving" @click="handleChangePassword">修改密码</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  AlarmClock,
  ArrowRight,
  Calendar,
  CircleCheck,
  CircleClose,
  Clock,
  EditPen,
  Iphone,
  Key,
  Lock,
  Message,
  OfficeBuilding,
  Postcard,
  School,
  Tickets,
  User
} from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { getUserStats, updateUserInfo, changePassword } from '@/api/user'
import { getFavoriteList } from '@/api/favorite'
import { getMyReservations } from '@/api/reservation'
import { useUserStore } from '@/stores/user'
import { formatDateTime } from '@/utils/time'
import { typeText } from '@/utils/dict'

const router = useRouter()
const userStore = useUserStore()
const userInfo = computed(() => userStore.userInfo)

/** 头像占位：取姓名末位，多字取后两字（与顶栏一致） */
const avatarText = computed(() => {
  const name = userInfo.value ? userInfo.value.name : ''
  return name ? name.slice(-2) : '客'
})

/** 时间感知问候（2025 趋势：个性化首屏，无 AI 味文案） */
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 5) return '夜深了'
  if (h < 9) return '早上好'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

/** 身份卡展示字段 */
const displayName = computed(() => (userInfo.value && userInfo.value.name) || '未设置姓名')
const studentNoText = computed(() => (userInfo.value && userInfo.value.studentNo) || '-')
const usernameText = computed(() => (userInfo.value && userInfo.value.username) || '-')
const emailText = computed(() => (userInfo.value && userInfo.value.email) || '')
const phoneText = computed(() => (userInfo.value && userInfo.value.phone) || '')

/** 数据概览 */
const stats = ref({})
const favLoading = ref(false)
const favorites = ref([])
const msgLoading = ref(false)
const notifications = ref([])

/** 最近一次预约展示（空值兜底"暂无"） */
const lastReserveText = computed(() =>
  stats.value.lastReservationTime ? formatDateTime(stats.value.lastReservationTime) : '暂无'
)

/** 编辑资料弹窗 */
const editDialogVisible = ref(false)
const infoFormRef = ref(null)
const infoSaving = ref(false)
const infoForm = reactive({ name: '', email: '', phone: '' })
const infoRules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [{ pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' }]
}

/** 修改密码弹窗 */
const pwdDialogVisible = ref(false)
const pwdFormRef = ref(null)
const pwdSaving = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const validateConfirm = (_rule, value, callback) => {
  if (value !== pwdForm.newPassword) {
    callback(new Error('两次输入的新密码不一致'))
  } else {
    callback()
  }
}
const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '新密码长度不能少于 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' }
  ]
}

/** 打开编辑资料弹窗（打开时清除历史校验痕迹） */
function openEditDialog() {
  editDialogVisible.value = true
}
function clearInfoValidate() {
  nextTick(() => infoFormRef.value && infoFormRef.value.clearValidate())
}

/** 打开修改密码弹窗（打开时清除历史校验痕迹） */
function openPwdDialog() {
  pwdDialogVisible.value = true
}
function clearPwdValidate() {
  nextTick(() => pwdFormRef.value && pwdFormRef.value.clearValidate())
}

/** 消息已读状态（localStorage，key 带用户维度） */
const READ_KEY = () => `reservation_msg_read_${userInfo.value ? userInfo.value.id : ''}`
function getReadIds() {
  try {
    return JSON.parse(localStorage.getItem(READ_KEY()) || '[]')
  } catch (e) {
    return []
  }
}
function setReadIds(ids) {
  localStorage.setItem(READ_KEY(), JSON.stringify(ids))
}
function isRead(id) {
  return getReadIds().includes(id)
}
const unreadCount = computed(() => notifications.value.filter((m) => !isRead(m.id)).length)

/** 加载数据概览 */
async function loadStats() {
  try {
    const res = await getUserStats()
    stats.value = res.data
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  }
}

/** 加载收藏列表 */
async function loadFavorites() {
  favLoading.value = true
  try {
    const res = await getFavoriteList()
    favorites.value = res.data
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  } finally {
    favLoading.value = false
  }
}

/**
 * 消息通知：由本人预约表状态动态生成（不新增通知表）
 *  - 待审核(0) → 待审核提醒
 *  - 已通过(1) → 审核结果通知（通过）+ 即将开始提醒（24 小时内）
 *  - 已驳回(2) → 审核结果通知（驳回，含审核备注）
 *  - 已取消(3) 不生成
 */
async function loadNotifications() {
  msgLoading.value = true
  try {
    const res = await getMyReservations({ page: 1, size: 50 })
    const list = res.data.records || []
    const now = dayjs()
    const messages = []
    list.forEach((r) => {
      const base = `您的「${r.classroomName}」预约（${r.reserveDate} ${r.startTime}-${r.endTime}）`
      if (r.status === 0) {
        messages.push({
          id: `pending_${r.id}`,
          type: 'pending',
          title: '预约待审核',
          content: `${base}已提交，等待管理员审核`,
          time: r.createTime
        })
      } else if (r.status === 1) {
        messages.push({
          id: `approved_${r.id}`,
          type: 'approved',
          title: '审核通过',
          content: `${base}已通过审核`,
          time: r.auditTime || r.createTime
        })
        // 即将开始提醒：已通过且开始时间在未来 24 小时内
        const start = dayjs(`${r.reserveDate} ${r.startTime}`)
        if (start.isAfter(now) && start.isBefore(now.add(24, 'hour'))) {
          messages.push({
            id: `upcoming_${r.id}`,
            type: 'upcoming',
            title: '预约即将开始',
            content: `您的「${r.classroomName}」预约将于 ${r.reserveDate} ${r.startTime} 开始，请准时到场`,
            time: r.auditTime || r.createTime
          })
        }
      } else if (r.status === 2) {
        messages.push({
          id: `rejected_${r.id}`,
          type: 'rejected',
          title: '审核驳回',
          content: `${base}被驳回：${r.auditRemark || '未填写原因'}`,
          time: r.auditTime || r.createTime
        })
      }
    })
    // 按时间倒序（无时间视为最早）
    messages.sort((a, b) => dayjs(b.time || 0).valueOf() - dayjs(a.time || 0).valueOf())
    notifications.value = messages
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  } finally {
    msgLoading.value = false
  }
}

/** 单条标记已读 */
function markRead(id) {
  const ids = getReadIds()
  if (!ids.includes(id)) {
    ids.push(id)
    setReadIds(ids)
  }
}

/** 全部标记已读 */
function markAllRead() {
  setReadIds(notifications.value.map((m) => m.id))
}

/** 保存个人信息（弹窗内提交，成功后同步最新用户信息） */
async function handleSaveInfo() {
  try {
    await infoFormRef.value.validate()
  } catch (e) {
    return
  }
  infoSaving.value = true
  try {
    const res = await updateUserInfo({
      name: infoForm.name,
      email: infoForm.email || null,
      phone: infoForm.phone || null
    })
    ElMessage.success(res.message || '个人信息修改成功')
    await userStore.fetchInfo()
    editDialogVisible.value = false
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  } finally {
    infoSaving.value = false
  }
}

/** 修改密码（关键操作：二次确认；成功后清登录态重新登录） */
async function handleChangePassword() {
  try {
    await pwdFormRef.value.validate()
  } catch (e) {
    return
  }
  try {
    await ElMessageBox.confirm('确定要修改登录密码吗？修改成功后需要重新登录。', '修改密码确认', {
      confirmButtonText: '确定修改',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch (e) {
    return
  }
  pwdSaving.value = true
  try {
    const res = await changePassword({
      oldPassword: pwdForm.oldPassword,
      newPassword: pwdForm.newPassword,
      confirmPassword: pwdForm.confirmPassword
    })
    ElMessage.success(res.message || '密码修改成功')
    // 走统一登出：先请求后端注销 Redis 会话，再清除本地登录态（logout 内部已 clearAuth）
    await userStore.logout()
    router.push('/login')
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  } finally {
    pwdSaving.value = false
  }
}

/** 消息类型图标（图标化消息流） */
function msgIcon(type) {
  return { pending: Clock, approved: CircleCheck, rejected: CircleClose, upcoming: AlarmClock }[type] || Clock
}

/** 页面内平滑滚动（尊重 prefers-reduced-motion） */
function smoothScrollTo(el) {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  el.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' })
}

/** 常用教室 → 滚动到收藏区 */
function scrollToFav() {
  const el = document.getElementById('favorite-section')
  if (el) smoothScrollTo(el)
}

/** 跳转我的预约 */
function goMyReservations() {
  router.push('/student/my-reservations')
}

/** 跳转预约日历总览 */
function goCalendar() {
  router.push('/student/calendar')
}

/** 跳转教室详情 */
function goDetail(id) {
  router.push(`/student/classrooms/${id}`)
}

/** 去教室列表 */
function goClassroomList() {
  router.push('/student/home')
}

onMounted(async () => {
  await userStore.fetchInfo().catch(() => {})
  // 弹窗默认值来自最新用户信息
  infoForm.name = userInfo.value ? userInfo.value.name || '' : ''
  infoForm.email = userInfo.value ? userInfo.value.email || '' : ''
  infoForm.phone = userInfo.value ? userInfo.value.phone || '' : ''
  loadStats()
  loadFavorites()
  loadNotifications()
})
</script>

<style scoped>
/* ---- 页面标题栏 ---- */
.page-sub {
  font-size: 13px;
  color: var(--text-secondary);
  margin-top: 4px;
}

/* ---- ① 身份卡（个人名片：浅色块 + 细边框，扁平无渐变） ---- */
.identity-card {
  background: var(--brand-primary-lighter);
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-lg);
  padding: 22px 24px 10px;
  margin-bottom: 18px;
  box-shadow: var(--shadow-card);
}

.identity-row {
  display: flex;
  align-items: center;
  gap: 16px;
}

.identity-avatar {
  background: var(--brand-primary);
  color: #fff;
  font-size: 20px;
  font-weight: 600;
  flex-shrink: 0;
}

.identity-main {
  flex: 1;
  min-width: 0;
}

.identity-name-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}

.identity-name {
  font-size: 19px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 0.2px;
}

.identity-role {
  color: var(--brand-primary);
  border-color: var(--el-color-primary-light-7);
  background: transparent;
}

.identity-meta {
  font-size: 13px;
  color: var(--text-secondary);
}

.identity-edit {
  flex-shrink: 0;
}

/* ---- ② 数据概览条（身份卡下半区，分隔线 + 4 列） ---- */
.identity-divider {
  height: 1px;
  background: var(--border-color-light);
  margin: 18px -24px 0;
}

.overview-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  padding: 14px 0 12px;
}

.overview-item {
  min-width: 0;
}

.overview-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.2;
  letter-spacing: -0.4px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.overview-value-sm {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0;
  white-space: normal;
  line-height: 1.5;
}

.overview-label {
  font-size: 13px;
  color: var(--text-secondary);
  margin-top: 4px;
}

/* ---- 分区卡片通用 ---- */
.section-card {
  margin-bottom: 18px;
  border-radius: var(--radius-lg);
  scroll-margin-top: 76px; /* 顶部 sticky 栏高度补偿，滚动定位不被遮挡 */
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  gap: 12px;
  flex-wrap: wrap;
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.section-hint {
  font-size: 12px;
  color: var(--text-placeholder);
}

/* ---- ③ 快捷功能宫格 ---- */
.quick-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.quick-item {
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-md);
  padding: 16px 14px;
  cursor: pointer;
  background: #fff;
  transition: border-color 0.15s ease, background-color 0.15s ease, box-shadow 0.15s ease;
}

.quick-item:hover {
  border-color: var(--border-color);
  background: var(--brand-primary-lighter);
  box-shadow: var(--shadow-hover);
}

.quick-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.quick-info {
  min-width: 0;
}

.quick-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 3px;
}

.quick-desc {
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---- ④ 常用教室 ---- */
.fav-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
  min-height: 80px;
}

.fav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  cursor: pointer;
  background: #fff;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.fav-item:hover {
  border-color: var(--border-color);
  background: var(--brand-primary-lighter);
}

.fav-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.fav-info {
  min-width: 0;
}

.fav-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.fav-meta {
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---- ⑤ 消息通知（图标化消息流） ---- */
.msg-list {
  min-height: 80px;
}

.msg-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 12px;
  border-bottom: 1px solid var(--border-color-light);
  cursor: pointer;
  border-radius: var(--radius-md);
  transition: background-color 0.15s;
}

.msg-item:last-child {
  border-bottom: none;
}

.msg-item:hover {
  background: var(--brand-primary-lighter);
}

.msg-item.unread {
  background: var(--brand-primary-light);
}

.msg-item.unread:hover {
  background: var(--el-color-primary-light-8);
}

.msg-icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 2px;
}

.msg-icon-pending {
  background: var(--brand-warning-light);
  color: var(--brand-warning);
}

.msg-icon-approved {
  background: var(--brand-success-light);
  color: var(--brand-success);
}

.msg-icon-rejected {
  background: var(--brand-danger-light);
  color: var(--brand-danger);
}

.msg-icon-upcoming {
  background: var(--brand-primary-light);
  color: var(--brand-primary);
}

.msg-body {
  flex: 1;
  min-width: 0;
}

.msg-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.msg-content {
  font-size: 13px;
  color: var(--text-regular);
  margin: 2px 0;
  word-break: break-all;
}

.msg-time {
  font-size: 12px;
  color: var(--text-placeholder);
}

.msg-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand-danger);
  flex-shrink: 0;
  margin-top: 8px;
}

/* ---- ⑥ 账户与安全（分组设置列表，行点击弹窗编辑） ---- */
.settings-group {
  margin-bottom: 18px;
}

.settings-group:last-child {
  margin-bottom: 0;
}

.settings-group-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 8px;
}

.settings-list {
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-md);
  background: #fff;
  overflow: hidden;
}

.settings-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 14px;
  border-bottom: 1px solid var(--border-color-light);
  min-width: 0;
}

.settings-row:last-child {
  border-bottom: none;
}

.settings-row.readonly {
  cursor: default;
}

.settings-row.clickable {
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.settings-row.clickable:hover {
  background: var(--brand-primary-lighter);
}

.settings-icon {
  width: 34px;
  height: 34px;
  border-radius: 8px;
  background: var(--brand-primary-light);
  color: var(--brand-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.settings-label {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
  flex-shrink: 0;
}

.settings-value {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--text-regular);
  text-align: right;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.settings-value.muted {
  color: var(--text-placeholder);
}

.settings-chevron {
  color: var(--text-placeholder);
  font-size: 14px;
  flex-shrink: 0;
}

/* ---- 弹窗表单 ---- */
.account-card :deep(.el-dialog) {
  border-radius: var(--radius-lg);
}

/* ---- 响应式：窄屏降为单列 ---- */
@media (max-width: 900px) {
  .overview-row {
    grid-template-columns: repeat(2, 1fr);
    row-gap: 14px;
  }

  .quick-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 560px) {
  .quick-grid {
    grid-template-columns: 1fr;
  }
}
</style>
