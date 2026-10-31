/**
 * 차트 범례 접근성 E2E (WD-414)
 *
 * 결함: 범례 formatter 가 글자색을 입히려고 React 요소를 돌려줬는데, recharts 가 그 결과를 아이콘 aria-label 에
 * 문자열로 이어 붙여 스크린리더가 계열마다 「[object Object] legend icon」을 읽었다.
 * 이제 formatter 없이 계열 이름이 아이콘 이름이 되고, 글자색(#375 — 본문 전경색)은 CSS 로 준다.
 */

import type { Page } from '@playwright/test';

import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 숫자 두 계열(amount·cnt)을 가진 쿼리를 실행해 미리보기를 그린다 */
async function runQuery(page: Page) {
  await setupNewChartBuilderMocks(page);
  await mockApi(
    page,
    'POST',
    '/api/v1/analytics/queries/1/execute',
    createQueryResult({
      columns: ['region', 'amount', 'cnt'],
      rows: [
        { region: '서울', amount: 10, cnt: 3 },
        { region: '부산', amount: 20, cnt: 5 },
      ],
      totalRows: 2,
    }),
  );
  await page.goto('/analytics/charts/new');
  await page.getByRole('combobox').first().click();
  await page.getByRole('option', { name: '저장 쿼리 1' }).click();
  await page.getByRole('button', { name: '쿼리 실행' }).click();
  await expect(page.getByText('3개 컬럼, 2개 행 로드됨')).toBeVisible();
}

/** 범례 아이콘 이름에 「[object Object]」가 없고, 계열 이름으로 시작하며, 글자는 본문 전경색이다 */
async function expectReadableLegend(page: Page) {
  const legend = page.locator('.recharts-legend-wrapper');
  const icons = legend.locator('svg.recharts-surface');
  await expect(icons.first()).toBeAttached();
  const labels = await icons.evaluateAll((els) => els.map((el) => el.getAttribute('aria-label') ?? ''));
  expect(labels.length).toBeGreaterThan(0);
  for (const label of labels) {
    expect(label).not.toContain('[object Object]');
  }
  // 계열 이름(또는 파이의 항목 이름)이 아이콘 이름에 들어 있다
  const texts = await legend.locator('.recharts-legend-item-text').allInnerTexts();
  expect(labels.map((l) => l.replace(/ legend icon$/, ''))).toEqual(texts);

  // #375: 글자는 시리즈 색이 아니라 --foreground 다
  const [textColor, foreground] = await legend
    .locator('.recharts-legend-item-text')
    .first()
    .evaluate((el) => {
      const probe = document.createElement('span');
      probe.style.color = 'var(--foreground)';
      document.body.appendChild(probe);
      const fg = getComputedStyle(probe).color;
      probe.remove();
      return [getComputedStyle(el).color, fg];
    });
  expect(textColor).toBe(foreground);
}

test.describe('차트 범례 접근성 (WD-414)', () => {
  // 레이더는 계열이 둘 이상일 때만 범례를 그려 자동 설정에 따라 달라지므로 뺀다 — 같은 Legend 기본 렌더러를 쓴다
  for (const type of ['막대 차트', '선 차트', '영역 차트', '파이 차트', '산점도']) {
    test(`${type} 범례 아이콘이 계열 이름으로 읽히고 글자는 본문색이다`, async ({ authenticatedPage: page }) => {
      await runQuery(page);
      await page.getByRole('button', { name: type, exact: true }).click();
      await expectReadableLegend(page);
    });
  }
});
