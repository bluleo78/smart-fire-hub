import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import MCR from 'monocart-coverage-reports';

import { coverageOptions } from './coverage-config';

// 병렬 세션 간 E2E 실행 직렬화 (WD-257, smart-workplace e2e/global-lock.ts 이식).
// - workers 수(playwright.config.ts)는 "세션 1개"를 전제로 정했다. 여러 세션·워크트리가 동시에 E2E 를 돌리면
//   워커가 코어를 초과 구독해 지연 스파이크 → 타임아웃성 flaky 가 난다.
// - 워커 수를 더 낮추는 대신 세션 단위 뮤텍스로 "한 번에 한 세션만" 돌게 한다(동시 세션은 대기 후 순차 실행).
// - flock 이 macOS 기본 제공이 아니라 mkdir 원자성(디렉토리 생성은 OS 수준 배타적)으로 구현한다.
// - firehub 실행끼리만 막는다 — 다른 저장소가 쓰는 CPU 는 막지 못하고, 그 몫은 retries 가 흡수한다.
const LOCK_DIR = join(tmpdir(), 'smart-fire-hub-e2e.lock');
const PID_FILE = join(LOCK_DIR, 'pid');
const POLL_MS = 3000;
const LOG_EVERY_MS = 15000;

function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

/** 이전 실행이 비정상 종료(kill -9 등)해 락 디렉토리만 남았으면 정리한다. */
function reclaimIfStale(): void {
  if (!existsSync(PID_FILE)) return;
  const heldBy = Number(readFileSync(PID_FILE, 'utf-8').trim());
  if (Number.isFinite(heldBy) && !isProcessAlive(heldBy)) {
    rmSync(LOCK_DIR, { recursive: true, force: true });
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** 세션 락을 잡는다 — 다른 세션이 잡고 있으면 풀릴 때까지 기다린다. */
async function acquireLock(): Promise<void> {
  let lastLog = 0;
  for (;;) {
    reclaimIfStale();
    try {
      mkdirSync(LOCK_DIR);
      writeFileSync(PID_FILE, String(process.pid));
      return;
    } catch {
      const now = Date.now();
      if (now - lastLog > LOG_EVERY_MS) {
        console.log(`[e2e] 다른 세션이 E2E 실행 중 — 대기합니다 (락: ${LOCK_DIR})`);
        lastLog = now;
      }
      await sleep(POLL_MS);
    }
  }
}

/**
 * Playwright global setup
 * - 이 함수는 worker 프로세스 생성 이전에 메인 프로세스에서 한 번 실행된다.
 * - 세션 락을 잡고(CI 는 단일 러너라 생략), 반환하는 해제 함수가 전체 실행 끝에 락과 preview 빌드 산출물을 정리한다.
 * - E2E_COVERAGE=1 이면 이전 커버리지 캐시를 정리한다 — 기본 실행에서는 캐시 정리 오버헤드를 제거한다.
 */
export default async function globalSetup(): Promise<() => void> {
  if (!process.env.CI) await acquireLock();

  if (process.env.E2E_COVERAGE === '1') {
    const mcr = MCR(coverageOptions);
    mcr.cleanCache();
  }

  return () => {
    if (!process.env.CI) rmSync(LOCK_DIR, { recursive: true, force: true });
    // preview 모드 빌드 산출물(포트별 임시 디렉토리) 정리 — playwright.config.ts 참조.
    const previewOutDir = process.env.E2E_PREVIEW_OUT_DIR;
    if (previewOutDir) rmSync(previewOutDir, { recursive: true, force: true });
  };
}
