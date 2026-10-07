import type { Page } from '@playwright/test';

import { createLevels } from '../factories/security-level.factory';
import { mockApi } from './api-mock';

/** 등급 목록·사용량·내 자격 기본 모킹. 개별 테스트는 같은 경로를 다시 mockApi 해 덮어쓴다(나중 등록 우선). */
export async function setupSecurityLevelMocks(page: Page, opts: { myRank?: number } = {}) {
  const levels = createLevels();
  await mockApi(page, 'GET', '/api/v1/security-levels', levels);
  await mockApi(page, 'GET', '/api/v1/security-levels/usage', [
    { levelId: 1, datasetCount: 41, roleCount: 0 },
    { levelId: 2, datasetCount: 230, roleCount: 4 },
    { levelId: 3, datasetCount: 12, roleCount: 2 },
    { levelId: 4, datasetCount: 5, roleCount: 1 },
  ]);
  const myRank = opts.myRank ?? 4;
  await mockApi(page, 'GET', '/api/v1/security-levels/my-clearance', {
    rank: myRank,
    levelId: levels.find((l) => l.rank === myRank)?.id ?? null,
  });
}
