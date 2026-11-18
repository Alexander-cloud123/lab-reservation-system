<template>
  <div class="user-manage-page page-container">
    <!-- 页头：标题 + 说明 + 右侧提示 -->
    <div class="page-head">
      <div>
        <h1 class="page-title">用户管理</h1>
        <p class="page-sub">账号查询、启用/禁用与密码重置</p>
      </div>
      <p class="page-tip">当前登录的管理员账号不可禁用，也不可重置密码</p>
    </div>

    <!-- 筛选条：一张白面，控件同高 -->
    <div class="search-card">
      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="关键词">
          <el-input
            v-model="query.keyword"
            placeholder="账号 / 姓名 / 学号"
            clearable
            style="width: 220px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="query.role" placeholder="全部" clearable style="width: 132px">
            <el-option label="学生" :value="0" />
            <el-option label="管理员" :value="1" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 132px">
            <el-option label="正常" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 用户表格：白面表格容器 -->
    <div class="table-band">
      <div class="section-title">
        <span>账号列表</span>
        <span class="section-note">共 {{ total }} 个账号</span>
      </div>

      <el-table v-loading="loading" :data="records">
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="username" label="账号" min-width="100" />
        <el-table-column prop="name" label="姓名" min-width="88" />
        <el-table-column prop="studentNo" label="学号" min-width="112">
          <template #default="{ row }">{{ row.studentNo || '-' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" min-width="112">
          <template #default="{ row }">{{ row.phone || '-' }}</template>
        </el-table-column>
        <el-table-column prop="role" label="角色" width="80">
          <template #default="{ row }">
            <el-tag :type="row.role === 1 ? 'warning' : 'primary'">
              {{ row.role === 1 ? '管理员' : '学生' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="88">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '正常' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <template v-if="row.id === currentUserId">
              <el-tooltip content="不能操作当前登录的管理员账号" placement="top">
                <el-button link type="primary" disabled>禁用/启用</el-button>
              </el-tooltip>
              <el-button link type="primary" disabled>重置密码</el-button>
            </template>
            <template v-else>
              <el-button
                link
                :type="row.status === 1 ? 'danger' : 'primary'"
                @click="handleToggleStatus(row)"
              >
                {{ row.status === 1 ? '禁用' : '启用' }}
              </el-button>
              <el-button link type="primary" @click="handleResetPassword(row)">重置密码</el-button>
            </template>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-slate">
            没有找到符合条件的用户<span class="empty-action" @click="handleReset">重置筛选</span>
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
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { pageUsers, updateUserStatus, resetUserPassword } from '@/api/user'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const currentUserId = userStore.userInfo ? userStore.userInfo.id : null

const loading = ref(false)
const records = ref([])
const total = ref(0)

/** 查询条件（含分页） */
const query = reactive({
  page: 1,
  size: 10,
  keyword: '',
  role: null,
  status: null
})

/** 加载用户列表 */
async function loadData() {
  loading.value = true
  try {
    const params = {
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      role: query.role === null || query.role === '' ? undefined : query.role,
      status: query.status === null || query.status === '' ? undefined : query.status
    }
    const res = await pageUsers(params)
    records.value = res.data.records
    total.value = res.data.total
  } catch {
    // 统一错误提示已由 request.js 处理
  } finally {
    loading.value = false
  }
}

/** 查询（重置页码后加载） */
function handleSearch() {
  query.page = 1
  loadData()
}

/** 重置筛选条件 */
function handleReset() {
  query.keyword = ''
  query.role = null
  query.status = null
  query.page = 1
  loadData()
}

/** 启用/禁用（二次确认） */
async function handleToggleStatus(row) {
  const action = row.status === 1 ? '禁用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确定要${action}用户「${row.name}（${row.username}）」吗？${action === '禁用' ? '禁用后该用户将无法登录。' : ''}`,
      `${action}确认`,
      { confirmButtonText: `确定${action}`, cancelButtonText: '取消', type: 'warning' }
    )
    await updateUserStatus(row.id, row.status === 1 ? 0 : 1)
    ElMessage.success(`${action}成功`)
    loadData()
  } catch {
    // 用户取消或接口报错（统一提示）
  }
}

/** 重置密码（二次确认） */
async function handleResetPassword(row) {
  try {
    await ElMessageBox.confirm(
      `确定要将用户「${row.name}（${row.username}）」的密码重置为默认密码 123456 吗？`,
      '重置密码确认',
      { confirmButtonText: '确定重置', cancelButtonText: '取消', type: 'warning' }
    )
    await resetUserPassword(row.id)
    ElMessage.success('密码已重置为默认密码 123456')
  } catch {
    // 用户取消或接口报错（统一提示）
  }
}

/** 时间格式化 */
function formatTime(time) {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm') : '-'
}

onMounted(loadData)
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

/* 表格容器：白面 + 24px 内边距 */
.table-band {
  padding: var(--space-xl);
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
