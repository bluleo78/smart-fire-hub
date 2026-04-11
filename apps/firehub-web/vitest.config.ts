import react from '@vitejs/plugin-react';
import path from 'path';
import { defineConfig } from 'vitest/config';

// Vitest 단위 테스트 + v8 커버리지 설정 — 로컬 리포트 전용 (CI 연동 없음)
// E2E는 Playwright 소관이므로 여기서는 단위 테스트만 다룬다.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    passWithNoTests: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'json-summary'],
      // 단위 테스트는 coverage/unit, Playwright E2E는 coverage/e2e로 분리
      reportsDirectory: './coverage/unit',
      exclude: [
        'dist/**',
        'node_modules/**',
        '**/*.test.ts',
        '**/*.test.tsx',
        '**/*.d.ts',
        'vitest.config.ts',
        'vite.config.ts',
        'playwright.config.ts',
        'e2e/**',
        'src/main.tsx',
        'src/vite-env.d.ts',
        'src/components/ui/**',
      ],
    },
  },
});
