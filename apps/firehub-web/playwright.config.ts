import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E 테스트 설정
 * - Vite dev 서버를 자동 기동하고 Chromium에서 테스트 실행
 * - API는 page.route()로 모킹하므로 백엔드 서버 불필요
 */
export default defineConfig({
  testDir: './e2e',
  /* 테스트 실행 설정 */
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,

  /* HTML 리포터 — 실패 시 자동 열림 */
  reporter: 'html',

  use: {
    baseURL: 'http://localhost:5173',
    /* 실패 시 트레이스 수집 — 디버깅용 */
    trace: 'on-first-retry',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  /* Vite dev 서버 자동 기동 — 없으면 기동, 있으면 재사용 */
  webServer: {
    command: 'pnpm dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000, // Vite 기동 대기 최대 2분
  },
});
