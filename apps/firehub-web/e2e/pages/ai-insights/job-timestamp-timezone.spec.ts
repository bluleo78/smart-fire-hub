import { createJob } from '../../factories/ai-insight.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 스마트 작업 타임스탬프 타임존 해석 E2E 회귀 테스트 (#349)
 *
 * 백엔드는 `LocalDateTime`을 타임존 표기 없이 직렬화한다(`2026-07-30T14:01:12`). 프로젝트 계약상
 * 이 문자열은 **UTC**로 해석해야 하는데, 목록의 `timeAgo`는 `new Date(str)`로 브라우저 로컬(KST)
 * 파싱을, 상세의 `formatDate`는 UTC 파싱을 해서 같은 값이 두 화면에서 정확히 9시간 어긋났다.
 *
 * 이 스펙은 "타임존 없는 UTC 문자열"을 실제로 넣고 목록/상세가 같은 시각을 가리키는지 검증한다.
 * 하드코딩된 문자열이 아니라 현재 시각 기준으로 만들어야 상대시간("N분 전")을 단정할 수 있다.
 */

/** Date → 백엔드 직렬화 형식(타임존 표기 없는 UTC 벽시계) 문자열 */
function toServerLocalDateTime(d: Date): string {
  return d.toISOString().replace('Z', '').replace(/\.\d+$/, '');
}

test.describe('스마트 작업 타임스탬프 타임존 (#349)', () => {
  test('타임존 없는 UTC 문자열을 목록은 "N분 전"으로, 상세는 같은 시각으로 표시한다', async ({
    authenticatedPage: page,
  }) => {
    // 5분 전(UTC)에 실행된 것으로 꾸민다 — 목록은 "5분 전"이어야 한다
    const executedAt = new Date(Date.now() - 5 * 60 * 1000);
    const job = createJob({
      id: 1,
      name: '타임존 검증 작업',
      lastExecutedAt: toServerLocalDateTime(executedAt),
      // 다음 실행은 이 테스트 관심사가 아니므로 비운다
      nextExecuteAt: null,
    });

    await mockApi(page, 'GET', '/api/v1/proactive/jobs', [job]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs');

    // 목록 — timeAgo가 로컬 파싱을 하면 KST 브라우저에서 "9시간 전"이 되어 실패한다
    const row = page.getByRole('row').filter({ hasText: '타임존 검증 작업' });
    await expect(row).toContainText('5분 전');
    await expect(row).not.toContainText('시간 전');

    // 상세 — formatDate(UTC 파싱)와 목록의 해석이 일치해야 한다.
    // 미래 시각이 아니라 방금 지난 시각으로 렌더되는지, 로컬 표기 문자열로 직접 확인한다.
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1', job);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', []);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await page.goto('/ai-insights/jobs/1');

    // 기대 문자열은 브라우저 안에서 만든다 — Node와 브라우저의 ko-KR ICU 출력이 달라
    // 테스트 프로세스에서 만든 문자열로 비교하면 표기 차이로 헛되이 깨진다
    const expectedLocal = await page.evaluate(
      (iso) => new Date(iso).toLocaleString('ko-KR'),
      executedAt.toISOString(),
    );
    await expect(page.getByText('마지막 실행')).toBeVisible();
    await expect(page.getByText(expectedLocal, { exact: false })).toBeVisible();
  });
});
