import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5174,
    proxy: {
      '/api': {
        target: process.env.GATEWAY_URL || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
