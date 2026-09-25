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
app.use(ElementPlus, { locale: zhCn })
// 全局指令：为 div 式可点击卡片/列表项补齐键盘可达性与按钮语义（详见 directives/clickable.js）
app.directive('clickable', clickable)
app.mount('#app')
