import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      // 页脚的服务状态依赖 readiness 探针，生产环境由 nginx 反代，本地开发也要转发
      '/actuator': 'http://localhost:8080',
    },
  },
})
