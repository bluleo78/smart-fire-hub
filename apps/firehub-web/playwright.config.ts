import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { defineConfig, devices } from '@playwright/test';

import { HARDWARE_GL, USE_HARDWARE_GL } from './e2e/gpu-launch';

/**
 * Playwright E2E 테스트 설정
 * - Vite 서버(dev 또는 preview)를 자동 기동하고 Chromium에서 테스트 실행
 * - API는 page.route()로 모킹하므로 백엔드 서버 불필요
 * - 부하 대비 설정(포트 격리·세션 락·재시도·타임아웃)은 smart-workplace 의 검증된 설정을 옮겨 왔다(WD-257)
 */
const CI = !!process.env.CI;

// E2E 전용 포트.
// - CI: 단일 러너이므로 고정 5173.
// - 로컬: 여러 세션·워크트리가 동시에 E2E/훅을 돌려도 같은 서버(남의 코드)를 물지 않도록 런마다 고유 포트로 격리한다
//   (20000~29999). PW_PORT 로 직접 지정할 수도 있다.
//   ⚠️ config 는 main + 각 worker 프로세스에서 따로 평가되므로 worker 마다 난수를 새로 뽑으면 baseURL≠서버 포트가 된다.
//   main 에서 한 번만 정해 process.env.PW_PORT 에 심고, 스폰되는 worker 가 이를 상속하게 한다.
if (!CI && !process.env.PW_PORT) {
  process.env.PW_PORT = String(20000 + Math.floor(Math.random() * 10000));
}
const PW_PORT = process.env.PW_PORT ?? '5173';
const BASE_URL = `http://localhost:${PW_PORT}`;

// E2E 서버 모드(E2E_SERVER=preview 로 선택, 기본 dev).
// - dev: Vite dev 서버. 요청마다 온디맨드 트랜스폼이고, 테스트마다 새 브라우저 컨텍스트가 번들 안 된 모듈 수백 개를
//   다시 받는다. 부하 시 무거운 lazy 라우트의 첫 렌더가 5초를 넘긴다. 기동이 즉시라 소수 spec·디버그(ui/headed)에 맞다.
// - preview: 한 번 번들한 결과물(빌드 ~15초)을 vite preview 로 정적 서빙 — 전체 스위트를 도는 훅에서 쓴다.
//   병렬 세션이 dist/ 를 서로 덮어쓰지 않도록 포트별 임시 디렉토리로 빌드하고 global-setup 의 해제 함수에서 지운다.
// - 커버리지(E2E_COVERAGE=1)는 dev 서버에서만 검증했다 — preview 와 함께 쓰지 않는다.
const PREVIEW = process.env.E2E_SERVER === 'preview';
const PREVIEW_OUT_DIR = join(tmpdir(), `firehub-web-e2e-dist-${PW_PORT}`);
process.env.E2E_PREVIEW_OUT_DIR = PREVIEW ? PREVIEW_OUT_DIR : '';

export default defineConfig({
  testDir: './e2e',
  outputDir: '../../test-results/e2e',
  /* 세션 락(병렬 세션 직렬화) + V8 커버리지 캐시 정리 — global setup, teardown에서 커버리지 리포트 생성 */
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',
  /* 테스트 실행 설정 */
  fullyParallel: true,
  forbidOnly: CI,
  // 로컬 1회 재시도 = 게이트 안전망. 다른 저장소의 빌드·테스트가 CPU 를 점유해 생기는 일시 정지(클릭 30초 대기 등)는
  // 테스트 코드로 막을 수 없어 재시도로 흡수한다. 재시도 통과는 'flaky' 로 보고되고 pre-push 가
  // scripts/e2e-flaky-record.mjs 로 누적 기록한다 — 자주 흔들리는 spec 은 기록을 보고 구조적으로 고친다.
  retries: CI ? 2 : 1,
  // 이 머신은 여러 자율 세션(smart-fire-hub·smart-workplace 등)이 동시에 코어를 나눠 쓰므로,
  // 코어 비율이 아니라 고정 개수로 둔다(과거 80%=8개가 동시 실행 중인 다른 세션과 경합해
  // 리소스 부족으로 pre-push Gradle 테스트 워커가 죽는 원인이 됐다). 워커마다 Chromium 이 붙어
  // 4 에서도 포화됐다 — smart-workplace 와 같이 3 으로 둔다.
  workers: CI ? 1 : 3,

  /* HTML 리포터 — 실패 시 자동 열림 */
  reporter: 'html',

  // 단언 기본 타임아웃 5초는 부하 시 lazy 라우트 청크 도착 지연(본문이 빈 채로 5초 경과)에 걸려 비결정적 실패를 낸다.
  // 10초로 올려 통과 경로 속도엔 영향 없이 지연 스파이크를 흡수한다. toPass 는 기본값이 테스트 타임아웃(30초)이라
  // 회귀 시 한 시도에 30초씩 돈다 — 단언 타임아웃과 맞춘다.
  expect: { timeout: 10_000, toPass: { timeout: 10_000 } },

  use: {
    baseURL: BASE_URL,
    // 액션(클릭 등)도 부하 시 지연·일시적 오버레이를 넘기도록 재시도 창을 넓힌다.
    actionTimeout: 15_000,
    // on-first-retry 는 로컬 retries:0 일 때 trace 를 하나도 남기지 않아 플래키 원인을 추적할 수 없었다 — 실패 시 항상 보존한다.
    trace: 'retain-on-failure',
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

  /* Vite 서버 자동 기동 — 병렬 세션 격리를 위해 기존 서버는 재사용하지 않는다(매 런 자체 서버) */
  webServer: {
    // --strictPort: 포트가 점유돼 있으면(드문 난수 충돌) 다른 포트로 새지 않고 즉시 실패한다.
    // preview 는 tsc 없이 vite build 만 한다(타입 검사는 typecheck 게이트 담당). preview 서버는 server.proxy 를 물려받는다.
    command: PREVIEW
      ? `pnpm exec vite build --outDir ${PREVIEW_OUT_DIR} --emptyOutDir --logLevel warn && pnpm exec vite preview --outDir ${PREVIEW_OUT_DIR} --port ${PW_PORT} --strictPort`
      : `pnpm exec vite --port ${PW_PORT} --strictPort`,
    // E2E=1 — vite.config.ts 가 /api 프록시를 닫힌 포트로 돌리고 __E2E__ 빌드 상수를 켠다.
    env: { E2E: '1' },
    url: BASE_URL,
    reuseExistingServer: false,
    // preview 는 빌드 시간(부하 시 1분+)까지 포함해 기다린다.
    timeout: PREVIEW ? 300_000 : 120_000,
  },
});
