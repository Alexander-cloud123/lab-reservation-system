/**
 * v-clickable：为承载 @click 的非交互元素（div / span 等）补齐按钮语义与键盘可达性。
 *
 * 使用场景：卡片式、列表项式容器用 div 承接点击，鼠标可用但键盘与读屏用户完全不可达。
 * 本指令只新增属性与键盘事件，不改变 DOM 结构、类名与原有 @click 逻辑，因此不影响既有选择器。
 * 焦点样式复用 main.css 中全局的 `[tabindex]:focus-visible` 规则，无需额外 CSS。
 */

/** 触发点击的按键：Enter 与空格（与原生 button 行为一致） */
const ACTIVATE_KEYS = ['Enter', ' ']

export const clickable = {
  mounted(el) {
    el.setAttribute('role', 'button')
    // 元素已在 Tab 序列中时（如原生 a/button）不覆盖其 tabindex
    if (!el.hasAttribute('tabindex')) {
      el.setAttribute('tabindex', '0')
    }
    el.addEventListener('keydown', (e) => {
      if (ACTIVATE_KEYS.includes(e.key)) {
        e.preventDefault() // 空格默认滚动页面，需阻止
        e.currentTarget.click()
      }
    })
  }
}