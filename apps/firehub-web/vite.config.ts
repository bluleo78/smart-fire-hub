import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import { visualizer } from 'rollup-plugin-visualizer'
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

// https://vite.dev/config/
export default defineConfig({
  customLogger: logger,
  plugins: [
    react(),
    tailwindcss(),
    visualizer({ open: false, gzipSize: true, filename: 'dist/stats.html' }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8090',
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
