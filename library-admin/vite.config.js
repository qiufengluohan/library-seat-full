import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

const BACKEND = 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 3000,
    open: true,
    proxy: {
      // REST 接口转发到 library-seat
      '/api': {
        target: BACKEND,
        changeOrigin: true
      },
      // WebSocket /ws/seats，必须开 ws 才能完成协议升级
      '/ws': {
        target: BACKEND,
        changeOrigin: true,
        ws: true
      }
    }
  }
})
