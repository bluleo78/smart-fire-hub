import MCR from 'monocart-coverage-reports';

import { coverageOptions } from './coverage-config';

/**
 * Playwright global teardown
 * - 모든 테스트가 끝난 뒤 누적 캐시된 커버리지 데이터를 리포트로 생성한다.
 * - worker 들이 `mcr.add()` 로 같은 outputDir/.cache 에 쌓아둔 데이터를 merge 한다.
 */
export default async function globalTeardown() {
  const mcr = MCR(coverageOptions);
  await mcr.generate();
}
