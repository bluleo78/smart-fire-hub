import MCR from 'monocart-coverage-reports';

import { coverageOptions } from './coverage-config';

/**
 * Playwright global setup
 * - 테스트 실행 전 이전 커버리지 캐시를 정리한다.
 * - 이 함수는 worker 프로세스 생성 이전에 메인 프로세스에서 한 번 실행된다.
 * - E2E_COVERAGE=1 일 때만 동작한다 — 기본 실행에서는 캐시 정리 오버헤드를 제거한다.
 */
export default async function globalSetup() {
  if (process.env.E2E_COVERAGE !== '1') return;
  const mcr = MCR(coverageOptions);
  mcr.cleanCache();
}
