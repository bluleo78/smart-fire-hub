import type { Page } from '@playwright/test';

import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 차트 빌더 쿼리 실행 완료 상태 메시지 회귀 테스트 (WD-916, WCAG SC 4.1.3)
 *
 * 「N개 컬럼, N개 행 로드됨」은 실행 직후 처음 나타나는 문구라, 스크린리더는 이미 있던 live region 의
 * 텍스트 변화만 알린다. 그래서 문구를 role="status" 그릇에 늘 두고 그 안의 텍스트만 바꾼다.
 */

const ROWS = [
  { label: '항목 1', v: 7 },
  { label: '항목 2', v: 1 },
  { label: '항목 3', v: 8 },
];

/**
 * 새 차트 빌더를 열고 「저장 쿼리 1」을 고른 뒤, 실행 버튼·그 카드·카드 안 상태 그릇을 돌려준다.
 * truncatedFor(n) 은 n 번째 실행 응답의 잘림 여부 — 주면 실행 API 를 모킹한다.
 */
async function openBuilderWithQuery(page: Page, truncatedFor?: (call: number) => boolean) {
  await setupNewChartBuilderMocks(page);
  if (truncatedFor) {
    let call = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries/1/execute',
      (route) => {
        call += 1;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            createQueryResult({ columns: ['label', 'v'], rows: ROWS, totalRows: 3, truncated: truncatedFor(call) }),
          ),
        });
      },
    );
  }
  await page.goto('/analytics/charts/new');
  await page.getByRole('combobox').click();
  await page.getByRole('option', { name: '저장 쿼리 1' }).click();
  const runBtn = page.getByRole('button', { name: '쿼리 실행' });
  // 「쿼리 실행」 버튼이 든 카드 안으로 범위를 좁힌다(페이지의 다른 live region 과 구분)
  const card = page.locator('[data-slot="card-content"]').filter({ has: runBtn });
  return { runBtn, card, status: card.getByRole('status') };
}

test.describe('차트 빌더 쿼리 실행 완료 상태 메시지 (WD-916)', () => {
  test('실행 전에도 빈 상태 그릇이 있고, 실행하면 그 안에 컬럼·행 수가 나온다', async ({ authenticatedPage: page }) => {
    const { runBtn, status } = await openBuilderWithQuery(page, () => false);

    await expect(status).toHaveCount(1);
    await expect(status).toHaveText('');
    // 문구가 바뀔 때 일부가 아니라 전체를 정중히(polite) 읽도록 명시한다
    await expect(status).toHaveAttribute('aria-live', 'polite');
    await expect(status).toHaveAttribute('aria-atomic', 'true');

    await runBtn.click();
    await expect(status).toHaveText('2개 컬럼, 3개 행 로드됨');
  });

  test('다시 실행해 잘림이 풀리면 같은 그릇의 문구가 바뀐다', async ({ authenticatedPage: page }) => {
    const { runBtn, status } = await openBuilderWithQuery(page, (call) => call === 1);

    await runBtn.click();
    await expect(status).toHaveText('2개 컬럼, 상위 3개 행 로드됨 (행 상한 — 결과가 더 있을 수 있음)');
    // 그릇 DOM 노드에 표시를 달아 두었다가, 재실행 뒤에도 표시가 남아 있는지 본다(교체되면 live region 이 알리지 못함)
    await status.evaluate((el) => {
      el.dataset.probe = 'kept';
    });

    await runBtn.click();
    await expect(status).toHaveText('2개 컬럼, 3개 행 로드됨');
    await expect(status).toHaveAttribute('data-probe', 'kept');
  });

  test('결과가 없을 때 빈 그릇이 버튼 아래 여백을 늘리지 않는다', async ({ authenticatedPage: page }) => {
    const { runBtn, card } = await openBuilderWithQuery(page);

    const [btnBox, cardBox] = await Promise.all([runBtn.boundingBox(), card.boundingBox()]);
    // 고치기 전(origin/main)에는 버튼 아래 끝이 카드 아래 끝과 같았다(0px) — 빈 그릇이 12px 를 더하면 실패
    const gap = cardBox!.y + cardBox!.height - (btnBox!.y + btnBox!.height);
    expect(Math.abs(gap), `측정한 간격 gap=${gap}px (기준 0px)`).toBeLessThanOrEqual(1);
  });
});
