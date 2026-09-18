/**
 * ESLint 9 flat config（package.json "type": "module"）
 * 规则策略（代码质量重构轮约定）：
 *  - 仅启用 js.configs.recommended + vue flat/essential（不用 flat/recommended，避免既有代码海量告警阻塞）
 *  - 格式化类规则交给 Prettier，eslint-config-prettier 关闭冲突项
 *  - 首轮不加 --max-warnings；实际告警数作为后续收窄基线
 */
import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'
import eslintConfigPrettier from 'eslint-config-prettier'

export default [
  {
    ignores: ['dist/**', 'node_modules/**']
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.es2021
      }
    }
  },
  eslintConfigPrettier
]
