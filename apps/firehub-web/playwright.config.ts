import { defineConfig, devices } from '@playwright/test';

import { HARDWARE_GL, USE_HARDWARE_GL } from './e2e/gpu-launch';

/**
 * Playwright E2E 테스트 설정
 * - Vite dev 서버를 자동 기동하고 Chromium에서 테스트 실행
 * - API는 page.route()로 모킹하므로 백엔드 서버 불필요
 */
// 포트 파라미터화 — 기본 5173(하위호환). 멀티 워크트리에서 stale :5173(타 코드) 오염을 피해
// 격리 포트로 검증할 수 있도록 PW_PORT로 baseURL·webServer(dev 서버 포트)를 함께 바꾼다.
const PW_PORT = process.env.PW_PORT ?? '5173';
const BASE_URL = `http://localhost:${PW_PORT}`;

export default defineConfig({
  testDir: './e2e',
  outputDir: '../../test-results/e2e',
  /* V8 커버리지 수집 설정 — global setup에서 캐시 정리, teardown에서 리포트 생성 */
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',
  /* 테스트 실행 설정 */
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  // 이 머신은 여러 자율 세션(smart-fire-hub·smart-workplace 등)이 동시에 코어를 나눠 쓰므로,
  // 코어 비율이 아니라 고정 개수로 둔다(과거 80%=8개가 동시 실행 중인 다른 세션과 경합해
  // 리소스 부족으로 pre-push Gradle 테스트 워커가 죽는 원인이 됐다).
  workers: process.env.CI ? 1 : 4,

  /* HTML 리포터 — 실패 시 자동 열림 */
  reporter: 'html',

  use: {
    baseURL: BASE_URL,
    /* 실패 시 트레이스 수집 — 디버깅용 */
    trace: 'on-first-retry',
  },

  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // 근거·조건은 e2e/gpu-launch.ts, 주입 결과 단정은 e2e/webgl-renderer-guard.spec.ts.
        launchOptions: { args: USE_HARDWARE_GL ? [...HARDWARE_GL.args] : [] },
      },
    },
  ],

  /* Vite dev 서버 자동 기동 — 없으면 기동(PW_PORT), 있으면 재사용 */
  webServer: {
    command: `pnpm exec vite --port ${PW_PORT} --strictPort`,
    url: BASE_URL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000, // Vite 기동 대기 최대 2분
  },
});
