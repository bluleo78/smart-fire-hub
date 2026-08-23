import react from '@vitejs/plugin-react';
import path from 'path';
import { defineConfig } from 'vitest/config';

// Vitest 단위 테스트 + v8 커버리지 — 로컬 리포트 전용. E2E 는 Playwright 소관이다.
export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  test: {
    globals: true,
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./vitest.setup.ts'],
    passWithNoTests: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'json-summary'],
      reportsDirectory: './coverage/unit',
      exclude: [
        'dist/**', 'node_modules/**', '**/*.test.ts', '**/*.test.tsx', '**/*.d.ts',
        'vitest.config.ts', 'vite.config.ts', 'playwright.config.ts', 'e2e/**',
        'src/main.tsx', 'src/vite-env.d.ts', 'src/components/ui/**',
      ],
    },
  },
});
