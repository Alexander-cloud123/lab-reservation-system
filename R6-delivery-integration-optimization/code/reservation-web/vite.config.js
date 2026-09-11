import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 开发代理：/api 转发到后端 8080，前端无跨域问题
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    host: '127.0.0.1',
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    // element-plus 全量引入为 R1 既定设计（spec 3.1 注册 Element Plus），单库 783kB（gzip 253kB）
    // 属 UI 框架整体体积，非本轮可安全收敛范围；阈值上调至 800 避免误报，不隐藏真实异常体积
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      output: {
        // 手动分包（R6 联调优化）：按依赖库拆分 vendor chunk，
        // 避免 Vue/Element/ECharts 全部打入单一 index chunk 导致包体过大（依赖版本不变）
        manualChunks(id) {
          if (!id.includes('node_modules')) {
            return undefined
          }
          if (id.includes('element-plus') || id.includes('@element-plus')) {
            return 'element-plus'
          }
          if (id.includes('vue') || id.includes('vue-router') || id.includes('pinia')) {
            return 'vue-vendor'
          }
          if (id.includes('echarts')) {
            return 'echarts'
          }
          if (id.includes('fullcalendar')) {
            return 'fullcalendar'
          }
          if (id.includes('axios') || id.includes('dayjs')) {
            return 'util-vendor'
          }
          return undefined
        }
      }
    }
  }
})
