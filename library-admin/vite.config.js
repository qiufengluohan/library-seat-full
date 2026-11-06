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
    // 不加这行时，Windows 上 Vite 只会监听 IPv6 的 [::1]:3000，
    // 于是 localhost:3000 能开、但 127.0.0.1:3000 打不开（浏览器/工具默认走哪个不一定）。
    // 显式绑到 IPv4 回环，两种写法都能访问。
    host: '127.0.0.1',
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
