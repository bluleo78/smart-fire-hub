import { createSynonymDecision } from '../../factories/synonymDecision.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('근접쌍 동의어 검수 페이지', () => {
  test('대기열 목록이 렌더링된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/graphrag/synonym-decisions', [createSynonymDecision()]);

    await page.goto('/knowledge-graph/synonym-review');

    await expect(page.getByText('전기적 요인')).toBeVisible();
    await expect(page.getByText('분전반의 누전')).toBeVisible();
    await expect(page.getByText('0.707')).toBeVisible();
  });

  test('승인 클릭 시 approve API를 호출하고 목록에서 사라진다', async ({ authenticatedPage: page }) => {
    let approveCalled = false;
    await mockApi(page, 'GET', '/api/v1/graphrag/synonym-decisions', [createSynonymDecision()]);
    await page.route(
      (url) => url.pathname === '/api/v1/graphrag/synonym-decisions/1/approve',
      (route) => {
        approveCalled = true;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createSynonymDecision({ status: 'approved', decidedBy: 1 })),
        });
      },
    );

    await page.goto('/knowledge-graph/synonym-review');
    await expect(page.getByText('전기적 요인')).toBeVisible();

    // 승인 후 재조회는 빈 목록으로 모킹해 "사라짐"을 검증한다.
    await mockApi(page, 'GET', '/api/v1/graphrag/synonym-decisions', []);
    await page.getByRole('button', { name: '승인' }).click();

    await expect(page.getByText('검수 대기 중인 근접쌍이 없습니다.')).toBeVisible();
    expect(approveCalled).toBe(true);
  });

  test('거부 클릭 시 reject API를 호출한다', async ({ authenticatedPage: page }) => {
    let rejectCalled = false;
    await mockApi(page, 'GET', '/api/v1/graphrag/synonym-decisions', [createSynonymDecision()]);
    await page.route(
      (url) => url.pathname === '/api/v1/graphrag/synonym-decisions/1/reject',
      (route) => {
        rejectCalled = true;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createSynonymDecision({ status: 'rejected', decidedBy: 1 })),
        });
      },
    );

    await page.goto('/knowledge-graph/synonym-review');
    await page.getByRole('button', { name: '거부' }).click();

    await expect.poll(() => rejectCalled).toBe(true);
  });
});
