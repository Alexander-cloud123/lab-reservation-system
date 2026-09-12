<template>
  <div class="profile-page">
    <!-- 一、数据概览卡片 -->
    <el-row :gutter="16" class="stats-row">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-value">{{ stats.totalReservations ?? 0 }}</div>
          <div class="stat-label">累计预约</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-value">{{ stats.monthReservations ?? 0 }}</div>
          <div class="stat-label">本月预约</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-value">{{ stats.approvalRate ?? 0 }}%</div>
          <div class="stat-label">审核通过率</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-value stat-value-sm">{{ stats.lastReservationTime ? formatDateTime(stats.lastReservationTime) : '暂无' }}</div>
          <div class="stat-label">最近一次预约</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 二、常用教室快捷入口（我的收藏） -->
    <el-card shadow="never" class="section-card">
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
          <div class="fav-name">{{ fav.name }}</div>
          <div class="fav-meta">{{ fav.building }}-{{ fav.roomNo }} · {{ typeText(fav.type) }} · {{ fav.capacity }}人</div>
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

    <!-- 三、消息通知列表（由本人预约表状态动态生成，已读状态存 localStorage） -->
    <el-card shadow="never" class="section-card">
      <div class="section-head">
        <span class="section-title">消息通知</span>
        <div class="section-actions">
          <el-tag v-if="unreadCount > 0" type="danger" size="small">{{ unreadCount }} 条未读</el-tag>
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
          <el-tag :type="msgTagType(msg.type)" size="small" class="msg-tag">{{ msgTagText(msg.type) }}</el-tag>
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

    <el-row :gutter="16">
      <!-- 四、个人信息表单 -->
      <el-col :span="12">
        <el-card shadow="never" class="section-card">
          <div class="section-head">
            <span class="section-title">个人信息</span>
          </div>
          <el-form ref="infoFormRef" :model="infoForm" :rules="infoRules" label-width="80px">
            <el-form-item label="登录账号">
              <el-input :model-value="userInfo ? userInfo.username : ''" disabled />
            </el-form-item>
            <el-form-item label="学号">
              <el-input :model-value="userInfo ? userInfo.studentNo || '-' : '-'" disabled />
            </el-form-item>
            <el-form-item label="姓名" prop="name">
              <el-input v-model="infoForm.name" maxlength="20" placeholder="请输入真实姓名" />
            </el-form-item>
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="infoForm.email" maxlength="50" placeholder="请输入邮箱（选填）" />
            </el-form-item>
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="infoForm.phone" maxlength="11" placeholder="请输入 11 位手机号（选填）" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="infoSaving" @click="handleSaveInfo">保存修改</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <!-- 五、修改密码表单 -->
      <el-col :span="12">
        <el-card shadow="never" class="section-card">
          <div class="section-head">
            <span class="section-title">修改密码</span>
          </div>
          <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="80px">
            <el-form-item label="原密码" prop="oldPassword">
              <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入原密码" />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="不少于 6 位" />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
            </el-form-item>
            <el-form-item>
              <el-button type="warning" :loading="pwdSaving" @click="handleChangePassword">修改密码</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { getUserStats, updateUserInfo, changePassword } from '@/api/user'
import { getFavoriteList } from '@/api/favorite'
import { getMyReservations } from '@/api/reservation'
import { useUserStore } from '@/stores/user'
import { clearAuth } from '@/utils/auth'
import { formatDateTime } from '@/utils/time'

const router = useRouter()
const userStore = useUserStore()
const userInfo = computed(() => userStore.userInfo)

/** 数据概览 */
const stats = ref({})
const favLoading = ref(false)
const favorites = ref([])
const msgLoading = ref(false)
const notifications = ref([])

/** 个人信息表单 */
const infoFormRef = ref(null)
const infoSaving = ref(false)
const infoForm = reactive({ name: '', email: '', phone: '' })
const infoRules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [{ pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' }]
}

/** 修改密码表单 */
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

/** 保存个人信息 */
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
    clearAuth()
    userStore.logout()
    router.push('/login')
  } catch (e) {
    // 统一错误提示已由 request.js 处理
  } finally {
    pwdSaving.value = false
  }
}

/** 类型文案 */
function typeText(type) {
  return { 1: '普通教室', 2: '实验室', 3: '机房' }[type] || '未知'
}

/** 消息标签文案 */
function msgTagText(type) {
  return { pending: '待审核', approved: '已通过', rejected: '已驳回', upcoming: '即将开始' }[type] || type
}

/** 消息标签色 */
function msgTagType(type) {
  return { pending: 'warning', approved: 'success', rejected: 'danger', upcoming: 'primary' }[type] || 'info'
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
  // 表单默认值来自最新用户信息
  infoForm.name = userInfo.value ? userInfo.value.name || '' : ''
  infoForm.email = userInfo.value ? userInfo.value.email || '' : ''
  infoForm.phone = userInfo.value ? userInfo.value.phone || '' : ''
  loadStats()
  loadFavorites()
  loadNotifications()
})
</script>

<style scoped>
.profile-page {
  max-width: 1000px;
  margin: 0 auto;
}

.stats-row {
  margin-bottom: 16px;
}

.stat-card {
  text-align: center;
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: #1f3a93;
  line-height: 1.4;
}

.stat-value-sm {
  font-size: 16px;
  font-weight: 600;
}

.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 2px;
}

.section-card {
  margin-bottom: 16px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.fav-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  min-height: 80px;
}

.fav-item {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 10px 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.fav-item:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.15);
}

.fav-name {
  font-size: 14px;
  font-weight: 600;
  color: #1f3a93;
  margin-bottom: 4px;
}

.fav-meta {
  font-size: 12px;
  color: #909399;
}

.msg-list {
  min-height: 80px;
}

.msg-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 8px;
  border-bottom: 1px solid #f0f2f5;
  cursor: pointer;
}

.msg-item:last-child {
  border-bottom: none;
}

.msg-item.unread {
  background: #ecf5ff;
}

.msg-tag {
  flex-shrink: 0;
  margin-top: 2px;
}

.msg-body {
  flex: 1;
  min-width: 0;
}

.msg-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.msg-content {
  font-size: 13px;
  color: #606266;
  margin: 2px 0;
  word-break: break-all;
}

.msg-time {
  font-size: 12px;
  color: #c0c4cc;
}

.msg-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f56c6c;
  flex-shrink: 0;
  margin-top: 8px;
}
</style>
