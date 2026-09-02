import { createCategories } from '../factories/dataset.factory';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../fixtures/dataset.fixture';

/**
 * 토스트 종류별 배경색 회귀 테스트 (refs #435)
 *
 * 무엇: 성공 토스트와 오류 토스트의 실제 배경색이 서로 다른지 단언한다.
 * 왜:  App.tsx 가 Toaster 를 richColors 없이 렌더하고 있었고, 그 상태에서는
 *      components/ui/sonner.tsx 가 인라인으로 고정한 --normal-bg(= var(--popover))가
 *      모든 종류에 적용돼 성공·오류·경고 배경이 전부 같았다. 아이콘만 다르니 오류 토스트가
 *      시각적으로 오류로 읽히지 않는다 (06-feedback-states.md 토스트 설정 절).
 *      "richColors prop 이 붙어 있는가"를 단언하면 인라인 --normal-bg 가 그걸 덮어써도
 *      통과하는 공허한 테스트가 되므로 렌더된 배경색을 직접 읽어 비교한다.
 */

/** 데이터셋 상세(기본 정보 탭)에 필요한 공통 모킹. */
async function setupDetailPageMocks(page: import('@playwright/test').Page) {
  await setupDatasetDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', ['sample', 'test']);
}

/** 기본 정보 탭의 편집 폼에서 저장을 눌러 토스트를 띄우고, 그 배경색을 돌려준다. */
async function submitAndReadToastColor(page: import('@playwright/test').Page): Promise<string> {
  await expect(page.getByRole('heading', { name: '기본 정보' })).toBeVisible({ timeout: 15000 });
  await page.getByRole('button', { name: '수정' }).click();
  await page.getByLabel('데이터셋 이름 *').fill('색 검증용 이름');
  await page.getByRole('button', { name: '저장' }).click();

  const toast = page.locator('[data-sonner-toast]').first();
  await expect(toast).toBeVisible({ timeout: 10000 });
  return toast.evaluate((el) => getComputedStyle(el).backgroundColor);
}

test('성공 토스트와 오류 토스트의 배경색이 서로 다르다', async ({ authenticatedPage: page }) => {
  // 1) 성공 경로 — PUT 200
  await setupDetailPageMocks(page);
  await mockApi(page, 'PUT', '/api/v1/datasets/1', {});
  await page.goto('/data/datasets/1');
  const successBg = await submitAndReadToastColor(page);

  // 2) 오류 경로 — 같은 페이지에 PUT 400 을 덧등록하고(나중에 등록한 라우트가 우선) 다시 저장한다.
  //    새 탭(newPage)을 쓰면 인증 fixture 가 그 페이지에는 적용되지 않아 로그인으로 튕긴다.
  await mockApi(
    page,
    'PUT',
    '/api/v1/datasets/1',
    { message: '업데이트에 실패했습니다.' },
    { status: 400 },
  );
  await page.reload();
  const errorBg = await submitAndReadToastColor(page);

  // 두 색이 모두 실제로 읽혔는지 먼저 확인 — 투명이면 비교 자체가 공허하다.
  expect(successBg).toMatch(/^rgba?\(/);
  expect(errorBg).toMatch(/^rgba?\(/);
  expect(successBg).not.toBe('rgba(0, 0, 0, 0)');

  expect(
    errorBg,
    `성공(${successBg})과 오류(${errorBg}) 토스트 배경이 같다 — richColors 가 적용되지 않았다`,
  ).not.toBe(successBg);
});
