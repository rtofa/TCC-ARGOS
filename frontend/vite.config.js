import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Same origin in development: the browser calls /api and Vite forwards to the Spring Boot API,
    // so the backend needs no CORS configuration.
    proxy: { '/api': process.env.API_URL ?? 'http://localhost:8081' },
  },
})
