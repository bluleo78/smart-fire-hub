import MCR from 'monocart-coverage-reports';

import { coverageOptions } from './coverage-config';

/**
 * Playwright global setup
 * - 테스트 실행 전 이전 커버리지 캐시를 정리한다.
 * - 이 함수는 worker 프로세스 생성 이전에 메인 프로세스에서 한 번 실행된다.
 */
export default async function globalSetup() {
  const mcr = MCR(coverageOptions);
  mcr.cleanCache();
}
