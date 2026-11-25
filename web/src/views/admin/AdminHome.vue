<template>
  <el-container class="admin-layout">
    <!-- 顶部栏 -->
    <el-header class="admin-header">
      <div class="header-left">
        <button
          ref="menuTriggerRef"
          type="button"
          class="admin-menu-trigger"
          aria-label="打开管理菜单"
          aria-controls="admin-navigation"
          :aria-expanded="mobileMenuOpen"
          @click="openMobileMenu"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
            <path d="M4 7h16M4 12h16M4 17h16" stroke="currentColor" stroke-width="1.8" fill="none" />
          </svg>
          <span>菜单</span>
        </button>
        <div class="logo-badge" aria-hidden="true">预约</div>
        <span class="header-logo">高校实验室预约管理系统</span>
        <span class="role-chip">管理端</span>
      </div>
      <div class="header-right">
        <el-dropdown trigger="click" @command="handleUserCommand">
          <div class="user-entry">
            <el-avatar :size="30" class="user-avatar">{{ avatarText }}</el-avatar>
            <span class="user-name">{{ userInfo ? userInfo.name : '-' }}</span>
            <el-icon class="user-caret"><CaretBottom /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>
                <span class="user-account">账号：{{ userInfo ? userInfo.username : '-' }}</span>
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <el-icon><SwitchButton /></el-icon>退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <el-container class="admin-body">
      <button
        v-if="mobileMenuOpen"
        type="button"
        class="admin-menu-backdrop"
        tabindex="-1"
        aria-label="关闭管理菜单遮罩"
        @click="closeMobileMenu"
      />
      <!-- 桌面保留原侧栏；窄屏复用同一菜单变为可关闭浮层 -->
      <el-aside
        id="admin-navigation"
        ref="menuPanelRef"
        width="216px"
        class="admin-aside"
        :class="{ 'mobile-open': mobileMenuOpen }"
        aria-label="管理导航"
        :role="mobileMenuOpen ? 'dialog' : undefined"
        :aria-modal="mobileMenuOpen ? 'true' : undefined"
      >
        <div class="admin-mobile-head">
          <strong>管理菜单</strong>
          <button
            ref="menuCloseRef"
            type="button"
            class="admin-menu-close"
            aria-label="关闭管理菜单"
            @click="closeMobileMenu"
          >
            ×
          </button>
        </div>
        <el-menu :default-active="activeMenu" router class="admin-menu" @select="handleMenuSelect">
          <el-menu-item index="/admin/home">
            <span>首页</span>
          </el-menu-item>
          <el-menu-item index="/admin/users">
            <span>用户管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/classrooms">
            <span>教室资源管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/audits">
            <span>预约审核</span>
          </el-menu-item>
          <el-menu-item index="/admin/records">
            <span>预约记录</span>
          </el-menu-item>
          <el-menu-item index="/admin/dashboard">
            <span>数据看板</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <!-- 主内容区 -->
      <el-main class="admin-main">
        <router-view v-slot="{ Component, route: currentRoute }">
          <transition name="page-swap" mode="out-in">
            <component :is="Component" :key="currentRoute.path" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { CaretBottom, SwitchButton } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const userInfo = computed(() => userStore.userInfo)

/** 窄屏管理菜单：复用桌面侧栏，打开后限制焦点在浮层内 */
const mobileMenuOpen = ref(false)
const menuTriggerRef = ref(null)
const menuPanelRef = ref(null)
const menuCloseRef = ref(null)
// el-aside 的模板 ref 是组件实例，DOM 查询必须取 $el；否则焦点循环和跨断点聚焦会静默失效。
const menuPanelElement = computed(() => {
  const panel = menuPanelRef.value
  return panel ? (panel.$el || panel) : null
})
let menuMedia = null

function openMobileMenu() {
  mobileMenuOpen.value = true
  // 等抽屉进入可见状态后再移入焦点，避免下一帧样式重算把焦点退回触发按钮或 body。
  nextTick(() => {
    window.setTimeout(() => menuCloseRef.value && menuCloseRef.value.focus(), 240)
  })
}

function closeMobileMenu() {
  if (!mobileMenuOpen.value) return
  mobileMenuOpen.value = false
  nextTick(() => menuTriggerRef.value && menuTriggerRef.value.focus())
}

function handleMenuSelect() {
  closeMobileMenu()
}

function handleMenuMediaChange(event) {
  if (!event.matches && mobileMenuOpen.value) {
    mobileMenuOpen.value = false
    // 桌面端触发按钮会隐藏，焦点改回仍然可见的当前菜单项，避免落到 body。
    nextTick(() => {
      // 等断点样式与关闭过渡完成后再聚焦；否则原关闭按钮隐藏时，浏览器会把焦点重新送回 body。
      window.setTimeout(() => {
        const activeItem = menuPanelElement.value && menuPanelElement.value.querySelector('.el-menu-item.is-active')
        if (activeItem) activeItem.focus({ preventScroll: true })
      }, 240)
    })
  }
}

function onMenuKeydown(event) {
  if (!mobileMenuOpen.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    closeMobileMenu()
    return
  }
  if (event.key !== 'Tab' || !menuPanelElement.value) return

  const focusable = Array.from(
    menuPanelElement.value.querySelectorAll(
      'button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'
    )
  ).filter((element) => element.offsetParent !== null)
  if (!focusable.length) {
    event.preventDefault()
    return
  }
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const active = document.activeElement
  if (event.shiftKey && (active === first || !menuPanelElement.value.contains(active))) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && (active === last || !menuPanelElement.value.contains(active))) {
    event.preventDefault()
    first.focus()
  }
}

/** 头像占位：取姓名末位，多字取后两字 */
const avatarText = computed(() => {
  const name = userInfo.value ? userInfo.value.name : ''
  return name ? name.slice(-2) : '管'
})

/** 侧边菜单高亮：跟随当前路由 */
const activeMenu = computed(() => route.path)

onMounted(() => {
  // 拉取最新用户信息，验证 Token 有效性（失效时 request 拦截器自动跳登录页）
  userStore.fetchInfo().catch(() => {})
  window.addEventListener('keydown', onMenuKeydown)
  menuMedia = window.matchMedia('(max-width: 900px)')
  menuMedia.addEventListener('change', handleMenuMediaChange)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onMenuKeydown)
  if (menuMedia) {
    menuMedia.removeEventListener('change', handleMenuMediaChange)
  }
})

/** 用户下拉菜单指令 */
function handleUserCommand(cmd) {
  if (cmd === 'logout') {
    handleLogout()
  }
}

/** 退出登录（二次确认） */
async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await userStore.logout()
    router.push('/login')
  } catch {
    // 用户取消
  }
}
</script>

<style scoped>
.admin-layout {
  min-height: 100vh;
}

/* 顶栏：玻璃贴顶（四类玻璃容器之一） */
.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  padding: 0 32px;
  background: var(--glass);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  border-bottom: 1px solid var(--glass-line);
  box-shadow: var(--shadow-surface), var(--glass-sheen);
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.admin-menu-trigger,
.admin-mobile-head,
.admin-menu-backdrop {
  display: none;
}

.logo-badge {
  height: 28px;
  padding: 0 11px;
  border-radius: var(--radius-sm);
  background: var(--grad);
  color: #fff;
  box-shadow: 0 4px 14px rgba(29, 111, 224, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--font-family-sans);
  font-size: var(--text-sm);
  font-weight: 600;
  letter-spacing: 0.04em;
}

.header-logo {
  font-size: var(--text-md);
  font-weight: 600;
  line-height: 24px;
  color: var(--ink);
  letter-spacing: -0.01em;
}

.role-chip {
  padding-left: 10px;
  border-left: 1px solid var(--line-strong);
  font-size: var(--text-xs);
  line-height: 18px;
  color: var(--ink-soft);
  font-weight: 500;
}

.header-right {
  display: flex;
  align-items: center;
}

.user-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 10px;
  border-radius: var(--radius-control);
  transition: background-color var(--dur-1) var(--ease-out);
}
.user-entry:hover {
  background: rgba(255, 255, 255, 0.6);
}
.user-avatar {
  background: var(--grad);
  color: #fff;
  font-size: var(--text-xs);
  font-weight: 600;
  box-shadow: 0 4px 12px rgba(29, 111, 224, 0.28);
}
.user-name {
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--ink);
}
.user-caret {
  font-size: var(--text-xs);
  color: var(--ink-mute);
}
.user-account {
  font-size: var(--text-xs);
  line-height: 20px;
  color: var(--ink-mute);
}

.admin-body {
  height: calc(100vh - 60px);
}

/* 侧栏：玻璃 + 浮起 8px，活跃项渐变药丸（管理端最醒目的结构信号） */
.admin-aside {
  width: 216px;
  padding: 20px 14px;
  margin: 12px 0 12px 12px;
  border-radius: var(--radius-card);
  background: var(--glass);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  border: 1px solid var(--glass-line);
  box-shadow: var(--shadow-surface), var(--shadow-raised), var(--glass-sheen);
  overflow: visible;
}

.admin-menu {
  border-right: none;
  background: transparent;
}
.admin-menu .el-menu-item {
  position: relative;
  height: 42px;
  line-height: 42px;
  padding: 0 14px;
  border-radius: var(--radius-control);
  margin-bottom: 6px;
  font-size: var(--text-sm);
  color: var(--ink-soft);
  font-weight: 500;
}
.admin-menu .el-menu-item:hover {
  background: rgba(255, 255, 255, 0.62);
  color: var(--ink);
}
.admin-menu .el-menu-item.is-active {
  background: var(--grad);
  color: #fff;
  font-weight: 600;
  box-shadow: var(--shadow-brand);
}

.admin-main {
  background: transparent;
  padding: 0;
  overflow-y: auto;
}

@media (max-width: 900px) {
  .admin-header {
    padding: 0 var(--space-lg);
  }
  .header-left {
    min-width: 0;
  }
  .admin-menu-trigger {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    gap: 6px;
    height: 34px;
    padding: 0 10px;
    border: 1px solid var(--glass-line);
    border-radius: var(--radius-control);
    background: var(--glass-strong);
    color: var(--ink-soft);
    font: inherit;
    font-size: var(--text-sm);
    cursor: pointer;
  }
  .admin-menu-trigger:hover {
    border-color: var(--brand);
    color: var(--brand);
  }
  .admin-menu-backdrop {
    position: fixed;
    z-index: 1000;
    inset: 0;
    display: block;
    width: 100%;
    height: 100%;
    border: 0;
    background: var(--el-mask-color);
    cursor: default;
  }
  .admin-aside {
    position: fixed;
    z-index: 1001;
    top: 0;
    bottom: 0;
    left: 0;
    width: min(280px, calc(100vw - 48px)) !important;
    height: 100vh;
    height: 100dvh;
    margin: 0;
    padding: var(--space-md) 14px;
    overflow-y: auto;
    overscroll-behavior: contain;
    border-radius: 0 var(--radius-card) var(--radius-card) 0;
    visibility: hidden;
    pointer-events: none;
    transform: translateX(-102%);
    transition: transform var(--dur-2) var(--ease-out), visibility var(--dur-2) var(--ease-out);
  }
  .admin-aside.mobile-open {
    visibility: visible;
    pointer-events: auto;
    transform: translateX(0);
  }
  .admin-mobile-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-sm);
    margin-bottom: var(--space-md);
    padding: 0 var(--space-xs) var(--space-sm);
    border-bottom: 1px solid var(--line);
    color: var(--ink);
    font-size: var(--text-base);
  }
  .admin-menu-close {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border: 1px solid var(--line-strong);
    border-radius: var(--radius-control);
    background: var(--glass-strong);
    color: var(--ink-soft);
    font: inherit;
    font-size: var(--text-lg);
    line-height: 1;
    cursor: pointer;
  }
  .admin-menu-close:hover {
    border-color: var(--brand);
    color: var(--brand);
  }
  .admin-main {
    width: 100%;
    min-width: 0;
  }
}

@media (max-width: 640px) {
  .admin-header {
    padding: 0 12px;
  }
  .header-logo,
  .role-chip,
  .user-name {
    display: none;
  }
  .header-left {
    gap: var(--space-sm);
  }
  .user-entry {
    padding-right: 4px;
    padding-left: 4px;
  }
}
</style>