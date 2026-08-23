import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E 설정 (firehub-admin)
 * - API 는 page.route() 로 모킹하므로 백엔드 서버가 필요 없다.
 * - 기본 포트 5060: firehub-web 의 5173 과도, admin dev 서버 5050 과도 겹치지 않는
 *   격리 포트다. 겹치면 다른 워크트리/다른 앱의 stale 서버로 테스트가 돈다.
 */
const PW_PORT = process.env.PW_PORT ?? '5060';
const BASE_URL = `http://localhost:${PW_PORT}`;

export default defineConfig({
  testDir: './e2e',
  outputDir: '../../test-results/e2e-admin',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : '80%',
  reporter: 'html',
  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    // Chromium은 5060(SIP 표준 포트)을 unsafe port 로 하드코딩 차단한다(net::ERR_UNSAFE_PORT).
    // 격리 포트 5060을 그대로 쓰기 위해 이 포트만 명시적으로 허용한다.
    launchOptions: { args: [`--explicitly-allowed-ports=${PW_PORT}`] },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `pnpm exec vite --port ${PW_PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
