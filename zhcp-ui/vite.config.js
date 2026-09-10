import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 18089,
    proxy: {
      '/prod-api': {
        target: 'http://127.0.0.1:18088',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/prod-api/, '')
      }
    }
  }
})
