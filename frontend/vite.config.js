import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

const backend = process.env.PAPER_TRANSLATOR_BACKEND || 'http://127.0.0.1:8080'

export default defineConfig({
  plugins: [vue()],
  css: {
    preprocessorOptions: { scss: { api: 'modern-compiler' } }
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    rollupOptions: {
      output: {
        // Keep the UI library in its own long-cached chunk.
        manualChunks: { 'naive-ui': ['naive-ui'] }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: backend, changeOrigin: true }
    }
  }
})
