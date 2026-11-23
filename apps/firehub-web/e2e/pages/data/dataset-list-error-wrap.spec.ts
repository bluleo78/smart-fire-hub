import type { Locator, Page } from '@playwright/test';

import { createCategories, createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';
import { expectTableErrorAlert } from '../../fixtures/table-error-alert';
import { expectClearOfFade, expectContentInset, expectInside, lineHeightOf, rectOf } from '../../fixtures/table-visible-width';

/**
 * 데이터셋 목록 — 조회 실패 행의 긴 사유 줄바꿈 (WD-958)
 *
 * 표 스크롤 상자가 좁은 화면에서 가로로 스크롤될 때, 실패 행 문구가 한 줄로 늘어나
 * 「다시 시도」가 보이는 폭 밖으로 밀려났다. 내용은 보이는 폭 안에서 감겨야 한다.
 */
const LONG_REASON =
  '데이터셋 목록을 불러오는 중 내부 저장소 응답이 제한 시간을 넘겨 요청을 끝내지 못했습니다. 잠시 뒤 다시 시도하세요. trace=0123456789abcdef0123456789abcdef0123456789';

test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
});

/** 목록 조회를 500 + message 로 실패시키고 진입해 「다시 시도」가 보일 때까지 기다린다. */
async function gotoFailed(page: Page, message: string) {
  await mockApi(
    page,
    'GET',
    '/api/v1/datasets',
    { status: 500, error: 'Internal Server Error', message },
    { status: 500 },
  );
  await page.goto('/data/datasets');
  const retry = page.getByRole('button', { name: '다시 시도' });
  await expect(retry).toBeVisible();
  return {
    box: page.locator('[data-slot="table-container"]'),
    alert: page.getByRole('alert'),
    retry,
  };
}

test.describe('데이터셋 목록 — 실패 행 줄바꿈 (WD-958)', () => {
  test('375px 긴 사유는 여러 줄로 감기고 문구·「다시 시도」가 표 상자의 보이는 폭 안에 있다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 375, height: 720 });
    const { box, alert, retry } = await gotoFailed(page, LONG_REASON);
    const b = await rectOf(box);
    const a = await rectOf(alert);
    expect(a.height).toBeGreaterThan((await lineHeightOf(alert)) * 1.5);
    expectInside(a, b);
    expectInside(await rectOf(retry), b);
    await expectClearOfFade(page, a);
    await expectClearOfFade(page, await rectOf(retry));
    await expectContentInset(page);
    // 새 div 래퍼 뒤에도 alert 구조(td 안쪽·action 은 alert 밖)가 유지되는지
    await expectTableErrorAlert(page, LONG_REASON, { action: '다시 시도' });
  });

  test('375px 짧은 사유도 보이는 폭 안 한 줄', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 375, height: 720 });
    const { box, alert, retry } = await gotoFailed(page, '서버 오류');
    const b = await rectOf(box);
    const a = await rectOf(alert);
    expect(a.height).toBeLessThan((await lineHeightOf(alert)) * 1.5);
    expectInside(a, b);
    expectInside(await rectOf(retry), b);
    await expectClearOfFade(page, a);
    await expectClearOfFade(page, await rectOf(retry));
    await expectContentInset(page);
  });

  test('375px 표를 오른쪽 끝까지 밀어도 안내와 「다시 시도」가 보인다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 375, height: 720 });
    const { box, alert, retry } = await gotoFailed(page, LONG_REASON);
    // 스크롤 전제: 표가 보이는 폭보다 넓어야 하고, 밀면 실제로 스크롤돼야 한다
    expect(await box.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true);
    await box.evaluate((el) => {
      el.scrollLeft = el.scrollWidth;
    });
    expect(await box.evaluate((el) => el.scrollLeft)).toBeGreaterThan(0);
    const b = await rectOf(box);
    expectInside(await rectOf(alert), b);
    expectInside(await rectOf(retry), b);
    await expectClearOfFade(page, await rectOf(alert));
    await expectClearOfFade(page, await rectOf(retry));
    await expectContentInset(page);
  });

  // 리뷰 후속 ①: 표 스크롤 상자는 실패 행이 있을 때만 크기 컨테이너가 된다 — 늘 켜 두면 줄어드는 부모에서 표가 접히고
  // 셀 안 TruncatedText 의 100cqw 가 바깥 wrapper 대신 이 상자로 풀린다.
  test('표 스크롤 상자는 실패 행이 있을 때만 크기 컨테이너이고, 목록이 보일 때는 아니다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 375, height: 720 });
    const containerType = (l: Locator) => l.evaluate((el) => getComputedStyle(el).containerType);
    const { box } = await gotoFailed(page, LONG_REASON);
    expect(await containerType(box)).toBe('inline-size');

    // 같은 화면에서 다시 시도해 목록이 성공하면 컨테이너가 풀린다
    const datasets = createDatasets(2);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(datasets));
    await page.getByRole('button', { name: '다시 시도' }).click();
    await expect(page.getByText(datasets[0].name)).toBeVisible();
    expect(await containerType(box)).toBe('normal');
  });

  test('데스크톱 1280px 에서는 문구·버튼이 셀 가운데', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const { retry } = await gotoFailed(page, '서버 오류');
    const r = await rectOf(retry);
    const td = await rectOf(page.locator('td[colspan]').filter({ has: retry }));
    expect(Math.abs((r.left + r.right) / 2 - (td.left + td.right) / 2)).toBeLessThanOrEqual(1);
    await expectContentInset(page);
  });
});
