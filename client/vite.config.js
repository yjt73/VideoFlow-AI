import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '..', '')
  const backend = env.VITE_DEV_PROXY_TARGET || 'http://127.0.0.1:9090'
  return {
    envDir: '..',
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      proxy: Object.fromEntries(
        ['/user', '/media', '/analysis', '/admin', '/health'].map(path => [
          path,
          { target: backend, changeOrigin: true }
        ])
      )
    }
  }
})
