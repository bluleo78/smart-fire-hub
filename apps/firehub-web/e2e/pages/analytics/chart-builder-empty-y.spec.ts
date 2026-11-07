import type { Page } from '@playwright/test';

import { createQueryResult } from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 차트 빌더 — Y축 값 컬럼을 모두 해제했을 때의 미리보기·저장 회귀 테스트 (WD-505)
 *
 * 과거에는 미리보기가 비운 yAxis 를 결과 컬럼 전체(문자 컬럼 region 포함)로 자동 추론해, 게이지는 NaN,
 * 퍼널은 「서울: NaN」, 막대는 엉뚱한 문자 시리즈를 그렸다. 이제 미리보기는 추론하지 않고 빠진 축을 안내하며,
 * 저장도 같은 규칙·문구로 막는다.
 */

/** 문자 컬럼(region) + 숫자 컬럼(cnt, amt) — 추론하면 region 이 값으로 읽혀 NaN 이 된다 */
const QUERY_RESULT = createQueryResult({
  columns: ['region', 'cnt', 'amt'],
  rows: [
    { region: '서울', cnt: 5, amt: 100 },
    { region: '부산', cnt: 3, amt: 50 },
  ],
  totalRows: 2,
});

/** 새 차트 빌더에서 쿼리를 실행하고(막대 자동 추천: X=region, Y=cnt,amt) 원하는 차트 타입을 고른다 */
async function openBuilderWithQuery(page: Page, chartTypeLabel?: string) {
  await setupNewChartBuilderMocks(page);
  await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', QUERY_RESULT);
  await page.goto('/analytics/charts/new');
  await page.getByRole('combobox').click();
  await page.getByRole('option', { name: '저장 쿼리 1' }).click();
  await page.getByRole('button', { name: '쿼리 실행' }).click();
  await expect(page.getByText('3개 컬럼, 2개 행 로드됨')).toBeVisible();
  if (chartTypeLabel) {
    await page.getByRole('group', { name: '차트 타입' }).getByRole('button', { name: chartTypeLabel }).click();
  }
}

/** Y축 체크박스 그룹 */
function yAxisGroup(page: Page) {
  return page.getByRole('group', { name: 'Y축 (다중 선택)' });
}

/** 체크된 Y축 체크박스를 모두 해제한다 */
async function uncheckAllY(page: Page) {
  for (const col of ['cnt', 'amt']) {
    const box = yAxisGroup(page).getByRole('checkbox', { name: col });
    if ((await box.getAttribute('aria-checked')) === 'true') await box.click();
    await expect(box).toHaveAttribute('aria-checked', 'false');
  }
}

/** 미리보기 영역 — 「쿼리 실행」 안내가 사라진 뒤 ChartRenderer 가 그리는 칸 */
function preview(page: Page) {
  return page.getByTestId('chart-axis-required');
}

test.describe('차트 빌더 — Y축 모두 해제 시 안내 (WD-505)', () => {
  test('게이지: Y 를 모두 해제하면 NaN 대신 안내가 보이고, 다시 체크하면 게이지가 돌아온다', async ({
    authenticatedPage: page,
  }) => {
    await openBuilderWithQuery(page, '게이지');
    // 처음엔 Y(cnt) 가 있어 게이지가 그려진다
    await expect(page.locator('.recharts-surface').first()).toBeVisible();

    await uncheckAllY(page);
    await expect(preview(page)).toHaveText('값 컬럼(Y축)을 선택하세요.');
    await expect(page.locator('.recharts-surface')).toHaveCount(0);
    await expect(page.locator('main')).not.toContainText('NaN');

    // cnt 를 다시 체크하면 안내가 사라지고 게이지 수치(첫 행 cnt=5)가 그려진다
    await yAxisGroup(page).getByRole('checkbox', { name: 'cnt' }).click();
    await expect(preview(page)).toHaveCount(0);
    await expect(page.locator('.recharts-surface').first()).toBeVisible();
    await expect(page.locator('svg text').filter({ hasText: /^5$/ })).toBeVisible();
    await expect(page.locator('main')).not.toContainText('NaN');
  });

  test('퍼널: Y 를 모두 해제하면 「이름: NaN」 대신 안내가 보인다', async ({ authenticatedPage: page }) => {
    await openBuilderWithQuery(page, '퍼널');
    await expect(page.locator('.recharts-surface').first()).toBeVisible();

    await uncheckAllY(page);
    await expect(preview(page)).toHaveText('값 컬럼(Y축)을 선택하세요.');
    await expect(page.locator('main')).not.toContainText('NaN');

    // 단계 이름을 region 으로 두고 amt 를 다시 체크하면 단계 라벨이 실제 값으로 그려진다
    await page.getByLabel('X축', { exact: true }).click();
    await page.getByRole('option', { name: 'region', exact: true }).click();
    await yAxisGroup(page).getByRole('checkbox', { name: 'amt' }).click();
    await expect(preview(page)).toHaveCount(0);
    await expect(page.locator('svg text').filter({ hasText: '서울: 100' })).toBeVisible();
    await expect(page.locator('main')).not.toContainText('NaN');
  });

  test('막대: Y 를 모두 해제하면 추론된 문자 시리즈 없이 안내가 보이고, 다시 체크하면 차트가 돌아온다', async ({
    authenticatedPage: page,
  }) => {
    await openBuilderWithQuery(page, '막대 차트');
    await expect(page.locator('.recharts-surface').first()).toBeVisible();

    await uncheckAllY(page);
    await expect(preview(page)).toHaveText('Y축 컬럼을 선택하세요.');
    // 범례·막대가 남지 않는다(과거: region 을 시리즈로 추론한 범례가 그려짐)
    await expect(page.locator('.recharts-legend-wrapper')).toHaveCount(0);
    await expect(page.locator('.recharts-surface')).toHaveCount(0);
    await expect(page.locator('main')).not.toContainText('NaN');

    await yAxisGroup(page).getByRole('checkbox', { name: 'cnt' }).click();
    await expect(preview(page)).toHaveCount(0);
    await expect(page.locator('.recharts-surface').first()).toBeVisible();
  });

  test('게이지: Y 를 비운 채 저장하면 같은 안내로 막고 저장 요청을 보내지 않는다', async ({
    authenticatedPage: page,
  }) => {
    let writeCount = 0;
    page.on('request', (req) => {
      const path = new URL(req.url()).pathname;
      if ((req.method() === 'POST' || req.method() === 'PUT') && path.startsWith('/api/v1/analytics/charts')) {
        writeCount += 1;
      }
    });

    await openBuilderWithQuery(page, '게이지');
    await uncheckAllY(page);

    await page.getByRole('button', { name: '저장' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByPlaceholder('차트 이름을 입력하세요').fill('빈 게이지');
    await dialog.getByRole('button', { name: '만들기' }).click();

    // 미리보기와 같은 문구의 오류 토스트 — 미리보기 안내와 구분하려고 토스트 영역에서 찾는다
    await expect(page.locator('[data-sonner-toast]').filter({ hasText: '값 컬럼(Y축)을 선택하세요.' })).toBeVisible();
    await expect(dialog).toBeVisible();
    expect(writeCount).toBe(0);
  });
});
