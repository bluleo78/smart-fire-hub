import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import { visualizer } from 'rollup-plugin-visualizer'
import { createLogger, defineConfig } from 'vite'

/**
 * Playwright E2E 용 서버인지 — playwright.config.ts 의 webServer.env 가 E2E=1 을 넘긴다(WD-257).
 * CI 변수로 고르지 않는다: CI 가 운영 이미지를 빌드할 때도 켜져 테스트 전용 코드가 번들에 들어간다.
 */
const IS_E2E = process.env.E2E === '1'

/**
 * /api 프록시 대상. E2E 는 page.route() 모킹 누락 요청이 언제나 즉시 실패하도록 닫힌 포트(9, discard)로 보낸다.
 * 5010 으로 보내면 다른 세션이 띄운 실제 api 가 있을 때만 401·토큰 갱신이 끼어들어 결과가 환경 따라 달라진다.
 */
const API_TARGET = IS_E2E ? 'http://localhost:9' : 'http://localhost:5010'

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

// https://vite.dev/config/
export default defineConfig({
  customLogger: logger,
  // __E2E__: E2E 빌드에서만 테스트용 훅(그래프 인스턴스 노출 등)을 켠다 — src/types/build-constants.d.ts
  define: { __E2E__: JSON.stringify(IS_E2E) },
  plugins: [
    react(),
    tailwindcss(),
    // 번들 분석 리포트 — E2E preview 빌드는 임시 outDir 로 빌드하므로 저장소 dist/ 에 리포트를 남기지 않는다
    ...(IS_E2E ? [] : [visualizer({ open: false, gzipSize: true, filename: 'dist/stats.html' })]),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: API_TARGET,
        changeOrigin: true,
        // SSE 스트리밍 응답 버퍼링 방지
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes, _req, res) => {
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              res.setHeader('Cache-Control', 'no-cache');
              res.setHeader('Connection', 'keep-alive');
              res.setHeader('X-Accel-Buffering', 'no');
            }
          });
        },
      },
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'vendor-codemirror': [
            'codemirror',
            '@codemirror/lang-sql',
            '@codemirror/lang-python',
            '@codemirror/autocomplete',
            '@codemirror/search',
            '@codemirror/theme-one-dark',
            '@codemirror/state',
          ],
          'vendor-xyflow': ['@xyflow/react', '@dagrejs/dagre'],
          'vendor-markdown': ['react-markdown', 'remark-gfm'],
          'vendor-maplibre': ['maplibre-gl'],
        },
      },
    },
  },
})
