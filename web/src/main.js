// 应用入口（R1）：注册 Pinia 状态管理、Vue Router 与 Element Plus（全量引入，中文语言包）
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { clickable } from './directives/clickable'
import './assets/main.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
/**
 * Element Plus 2.7 的 zh-cn 语言包缺少 drawer / dialog / messagebox 的 close 键，
 * 关闭按钮的 aria-label 会退化成原始 key（如 "el.dialog.close"），读屏器会把英文 key 念出来。
 * 在官方中文包基础上补齐这三个键（其余键原样保留）——无障碍验收 A1/C1 机器代理发现。
 */
const elementPlusLocale = {
  ...zhCn,
  el: {
    ...zhCn.el,
    dialog: { ...(zhCn.el.dialog || {}), close: '关闭' },
    drawer: { ...(zhCn.el.drawer || {}), close: '关闭' },
    messagebox: { ...(zhCn.el.messagebox || {}), close: '关闭' }
  }
}
app.use(ElementPlus, { locale: elementPlusLocale })
// 全局指令：为 div 式可点击卡片/列表项补齐键盘可达性与按钮语义（详见 directives/clickable.js）
app.directive('clickable', clickable)
app.mount('#app')
