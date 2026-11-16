import { createJobExecution } from '../../factories/ai-insight.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 실행 상세 — 출처를 확인하지 못한 수치 경고 배너 (WD-767) E2E
 *
 * api 가 result.numberCheck 에 저장한 검증 결과를 배너로 보인다.
 * checked === true 이고 unverifiedTotal > 0 일 때만 보이고, 옛 실행(필드 없음)에는 보이지 않아야 한다.
 */

const DESCRIPTION =
  '리포트의 아래 수치를 이 실행의 조회 결과에서 찾지 못했습니다. 계산하거나 지어낸 값일 수 있으니 확인하세요.';

/** numberCheck 를 result 에 실은 COMPLETED 실행을 모킹하고 상세 페이지로 이동한다 */
async function openWith(
  page: import('@playwright/test').Page,
  numberCheck: unknown,
) {
  const result: Record<string, unknown> = {
    summary: '요약 본문',
    sections: [{ key: 'detail', type: 'text', label: '상세', content: '섹션 본문' }],
  };
  if (numberCheck !== undefined) result.numberCheck = numberCheck;
  await mockApi(
    page,
    'GET',
    '/api/v1/proactive/jobs/1/executions/1',
    createJobExecution({ id: 1, jobId: 1, status: 'COMPLETED', result }),
  );
  await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/1/html', '', { status: 404 });
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  await page.goto('/ai-insights/jobs/1/executions/1');
  await expect(page.getByTestId('execution-summary')).toBeVisible();
}

test.describe('실행 상세 — 출처 미확인 수치 경고 (WD-767)', () => {
  test('미확인 수치 2개면 제목·설명·수치·문맥이 보인다', async ({ authenticatedPage: page }) => {
    await openWith(page, {
      checked: true,
      unverifiedTotal: 2,
      unverified: [
        { text: '4.5억', context: '60일 내 마감 가중 4.5억 원' },
        { text: '100억', context: '연 100억 원 계약액' },
      ],
    });

    const banner = page.getByTestId('number-check-banner');
    await expect(banner).toContainText('출처를 확인하지 못한 수치 2개');
    await expect(banner).toContainText(DESCRIPTION);
    await expect(banner).toContainText('4.5억');
    await expect(banner).toContainText('60일 내 마감 가중 4.5억 원');
    await expect(banner).toContainText('100억');
    await expect(banner).toContainText('연 100억 원 계약액');
    await expect(banner).not.toContainText('외 ');
  });

  test('목록이 잘리면 총 개수를 제목에, 나머지를 「외 N개」로 보인다', async ({
    authenticatedPage: page,
  }) => {
    const unverified = Array.from({ length: 20 }, (_, i) => ({
      text: `${i + 1}00억`,
      context: `문맥 ${i + 1}`,
    }));
    await openWith(page, { checked: true, unverifiedTotal: 25, unverified });

    const banner = page.getByTestId('number-check-banner');
    await expect(banner).toContainText('출처를 확인하지 못한 수치 25개');
    await expect(banner.getByRole('listitem')).toHaveCount(20);
    await expect(banner).toContainText('외 5개');
  });

  test('checked 가 false 면 배너가 없다', async ({ authenticatedPage: page }) => {
    await openWith(page, {
      checked: false,
      unverifiedTotal: 1,
      unverified: [{ text: '4.5억', context: '문맥' }],
    });
    await expect(page.getByTestId('number-check-banner')).toHaveCount(0);
  });

  test('numberCheck 가 없는 옛 실행에는 배너가 없다', async ({ authenticatedPage: page }) => {
    await openWith(page, undefined);
    await expect(page.getByTestId('number-check-banner')).toHaveCount(0);
  });
});
