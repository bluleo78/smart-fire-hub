import type { Locator, Page } from '@playwright/test';

import { createCategories } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';
import { expectTableErrorAlert } from '../../fixtures/table-error-alert';

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

type Rect = { left: number; right: number; height: number };
const rectOf = (l: Locator) =>
  l.evaluate((el): Rect => {
    const r = el.getBoundingClientRect();
    return { left: r.left, right: r.right, height: r.height };
  });
const lineHeightOf = (l: Locator) => l.evaluate((el) => parseFloat(getComputedStyle(el).lineHeight));

function expectInside(inner: Rect, box: Rect) {
  expect(inner.right).toBeLessThanOrEqual(box.right + 0.5);
  expect(inner.left).toBeGreaterThanOrEqual(box.left - 0.5);
}

// 스크롤 페이드 폭(px) — table.tsx 페이드의 w-10(2.5rem)에 묶여 있다. 문구·버튼은 이 안쪽(1px 여유)에 있어야 한다.
const FADE = 40;
function expectClearOfFade(inner: Rect, box: Rect) {
  expect(inner.left).toBeGreaterThanOrEqual(box.left + FADE - 1);
  expect(inner.right).toBeLessThanOrEqual(box.right - FADE + 1);
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
    expectClearOfFade(a, b);
    expectClearOfFade(await rectOf(retry), b);
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
    expectClearOfFade(a, b);
    expectClearOfFade(await rectOf(retry), b);
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
    expectClearOfFade(await rectOf(alert), b);
    expectClearOfFade(await rectOf(retry), b);
  });

  test('데스크톱 1280px 에서는 문구·버튼이 셀 가운데', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const { retry } = await gotoFailed(page, '서버 오류');
    const r = await rectOf(retry);
    const td = await rectOf(page.locator('td[colspan]').filter({ has: retry }));
    expect(Math.abs((r.left + r.right) / 2 - (td.left + td.right) / 2)).toBeLessThanOrEqual(1);
  });
});
