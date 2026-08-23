import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E 설정 (firehub-admin)
 * - API 는 page.route() 로 모킹하므로 백엔드 서버가 필요 없다.
 * - 기본 포트 5051: firehub-web 의 5173 과도, admin dev 서버 5050 과도 겹치지 않는
 *   격리 포트다. 겹치면 다른 워크트리/다른 앱의 stale 서버로 테스트가 돈다.
 * - 5051 을 쓰지 않는 이유: Chromium 이 SIP 표준 포트(5051/5061)를 unsafe port 로 하드코딩
 *   차단한다(net::ERR_UNSAFE_PORT). --explicitly-allowed-ports 로 뚫을 수는 있지만, 그 플래그는
 *   이 설정을 복사해 가는 쪽이 함께 가져가야만 동작하는 보이지 않는 전제가 된다. 안전한 포트를
 *   고르면 전제가 없어진다.
 */
const PW_PORT = process.env.PW_PORT ?? '5051';
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
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `pnpm exec vite --port ${PW_PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
