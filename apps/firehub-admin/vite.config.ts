import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import { createLogger, defineConfig } from 'vite'

/**
 * 커스텀 로거 — 백엔드 없이 E2E 테스트 실행 시 발생하는 ECONNREFUSED proxy 경고를 억제한다.
 * Playwright page.route()로 모킹하지 못한 요청이 Vite proxy로 넘어와도 콘솔을 오염시키지 않는다.
 */
const logger = createLogger()
const originalError = logger.error.bind(logger)
logger.error = (msg, options) => {
  if (msg.includes('ECONNREFUSED')) return
  originalError(msg, options)
}

export default defineConfig({
  customLogger: logger,
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    // 5173 은 firehub-web 이 쓴다. admin dev 서버는 5050 고정(포트 충돌 시 조용히 다른 포트로
    // 흘러가면 CORS·프록시 전제가 깨지므로 strictPort 로 크게 실패시킨다).
    port: 5050,
    strictPort: true,
    proxy: {
      // admin 도 같은 firehub-api(5010)를 본다. 경로 접두어만 /api/platform 으로 다르다.
      '/api': {
        target: 'http://localhost:5010',
        changeOrigin: true,
      },
    },
  },
})
