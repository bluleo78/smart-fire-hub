import { createJob } from '../../factories/ai-insight.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 목록 — "다음 실행" 컬럼 E2E 회귀 테스트 (#348)
 *
 * 백엔드가 `next_execute_at`을 채우지 않아 전 작업이 상시 `-`였다. 백엔드가 값을 채우게 된 뒤
 * 프론트가 그 값을 올바른 타임존으로 해석해 표시하는지 검증한다. 서버는 타임존 표기 없는 UTC
 * 벽시계를 보내므로, `new Date()`로 파싱하면 9시간 어긋난다 (#349와 같은 계약).
 */

/** Date → 백엔드 직렬화 형식(타임존 표기 없는 UTC 벽시계) 문자열 */
function toServerLocalDateTime(d: Date): string {
  return d.toISOString().replace('Z', '').replace(/\.\d+$/, '');
}

/** 지금으로부터 N일 뒤, 서울 기준 09:00 정각의 UTC 시각 (서울 09:00 = UTC 00:00) */
function seoulNineAmInDays(days: number): Date {
  const d = new Date(Date.now() + days * 24 * 60 * 60 * 1000);
  d.setUTCHours(0, 0, 0, 0);
  return d;
}

test.describe('스마트 작업 목록 — 다음 실행 컬럼 (#348)', () => {
  test('next_execute_at 이 있으면 잡 타임존 기준 시각으로 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // 3일 뒤 서울 09:00 — "오늘/내일" 분기를 피해 "M월 D일 09:00" 형식으로 고정된다
    const nextRun = seoulNineAmInDays(3);
    const job = createJob({
      id: 1,
      name: '다음 실행 표시 작업',
      enabled: true,
      timezone: 'Asia/Seoul',
      nextExecuteAt: toServerLocalDateTime(nextRun),
    });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [job]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await page.goto('/ai-insights/jobs');

    const row = page.getByRole('row').filter({ hasText: '다음 실행 표시 작업' });

    // 값이 있는데도 '-'로 남던 것이 이 이슈의 현상 — 시각이 실제로 렌더돼야 한다
    await expect(row).toContainText('09:00');

    // 서울 09:00을 로컬 파싱하면 18:00이 되어 어긋난다 — 계약 위반 회귀 방지
    await expect(row).not.toContainText('18:00');
  });

  test('비활성 작업은 다음 실행이 없으므로 "-"로 표시된다', async ({ authenticatedPage: page }) => {
    // 비활성 잡은 스케줄이 해제되므로 백엔드가 next_execute_at 을 비운다
    const job = createJob({
      id: 2,
      name: '비활성 작업',
      enabled: false,
      timezone: 'Asia/Seoul',
      nextExecuteAt: null,
    });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [job]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await page.goto('/ai-insights/jobs');

    const row = page.getByRole('row').filter({ hasText: '비활성 작업' });
    await expect(row).toContainText('-');
    await expect(row).not.toContainText('09:00');
  });
});
