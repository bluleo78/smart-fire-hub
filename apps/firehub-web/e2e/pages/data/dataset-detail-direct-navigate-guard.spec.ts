/**
 * 데이터셋 상세 — 직접 navigate() 호출도 떠나기 가드를 거친다 E2E (WD-1174)
 *
 * 결함: 정보 탭에서 설명을 고치던 중 「쿼리 작성」·「차트 만들기」 버튼을 누르거나 재조회가 실패하면
 * navigate() 가 가드(useUnsavedChangesGuard)를 우회해 확인 없이 떠나 편집 내용이 사라졌다.
 * 수정: 세 자리 모두 requestNavigate 로 바꿔 편집 중에는 확인 창을 띄운다.
 *
 * 재조회 실패는 태그 추가(성공 시 상세 쿼리 무효화)로 일으킨다 — 창 포커스 재조회는 꺼져 있다.
 */

import type { Page } from '@playwright/test';

import { createCategories } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';
import { unsavedChangesDialog } from '../../fixtures/dialog.fixture';

const guardDialog = unsavedChangesDialog;

/** 재조회 실패 뒤 남은 화면의 낡은 데이터 안내 (리뷰 후속). */
const staleBanner = (page: Page) =>
  page.getByRole('status').filter({ hasText: '최신 데이터셋 정보를 불러오지 못했습니다' });

/** 상세 GET 을 실패로 바꿀 수 있는 모킹을 깔고 화면을 연다. */
async function openDetail(page: Page) {
  await setupDatasetDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await mockApi(page, 'POST', '/api/v1/datasets/1/tags', { id: 1, tags: ['신규태그'] });
  const control = { fail: false };
  // 기본 상세 모킹보다 나중에 등록해 이 라우트가 먼저 받는다
  await page.route(
    (url) => url.pathname === '/api/v1/datasets/1',
    (route) => {
      if (control.fail && route.request().method() === 'GET') {
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ status: 500, message: '서버 오류' }),
        });
      }
      return route.fallback();
    },
  );
  await page.goto('/data/datasets/1');
  await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
  return control;
}

/** 정보 탭에서 설명을 고쳐 편집 중(dirty) 상태로 만든다. */
async function startEditing(page: Page) {
  await page.getByRole('button', { name: '수정' }).click();
  await page.getByLabel('설명').fill('고친 설명');
}

/** 태그를 추가해 상세 재조회를 일으킨다. */
async function addTagToRefetch(page: Page) {
  await page.locator('button[title="태그 추가"]').click();
  const input = page.getByPlaceholder(/태그 입력|새 태그/);
  await input.fill('신규태그');
  await input.press('Enter');
}

test.describe('WD-1174 상세 직접 이동도 떠나기 가드를 거친다', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page);
  });

  test('편집 중 「쿼리 작성」을 누르면 확인 창이 뜨고, 취소하면 상세에 남아 편집이 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await openDetail(page);
    await startEditing(page);

    await page.getByRole('button', { name: '쿼리 작성' }).click();
    await expect(guardDialog(page)).toBeVisible();

    await guardDialog(page).getByRole('button', { name: '취소' }).click();
    await expect(guardDialog(page)).toBeHidden();
    await expect(page).toHaveURL(/\/data\/datasets\/\d+$/);
    await expect(page.getByLabel('설명')).toHaveValue('고친 설명');
  });

  test('편집 중 「차트 만들기」에서 이탈을 고르면 차트 새로 만들기로 간다', async ({ authenticatedPage: page }) => {
    await openDetail(page);
    await startEditing(page);

    await page.getByRole('button', { name: '차트 만들기' }).click();
    await expect(guardDialog(page)).toBeVisible();

    await guardDialog(page).getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL(/\/analytics\/charts\/new/);
  });

  test('편집 중 재조회가 실패하면 토스트는 뜨고 바로 떠나지 않고 확인 창을 띄운다', async ({
    authenticatedPage: page,
  }) => {
    const control = await openDetail(page);
    await startEditing(page);

    control.fail = true;
    await addTagToRefetch(page);

    await expect(page.getByText('데이터셋을 불러오지 못했습니다')).toBeVisible();
    await expect(guardDialog(page)).toBeVisible();
    await expect(page).toHaveURL(/\/data\/datasets\/\d+$/);

    // 취소하면 상세에 남고 편집 중이던 값도 유지된다
    await guardDialog(page).getByRole('button', { name: '취소' }).click();
    await expect(guardDialog(page)).toBeHidden();
    await expect(page).toHaveURL(/\/data\/datasets\/\d+$/);
    await expect(page.getByLabel('설명')).toHaveValue('고친 설명');
  });

  test('재조회 실패 확인 창에서 「취소」하면 낡은 데이터 안내가 남고, 편집을 끝내도 저절로 떠나지 않으며 안내의 「목록으로」로 간다 (리뷰 후속)', async ({
    authenticatedPage: page,
  }) => {
    const control = await openDetail(page);
    await startEditing(page);

    control.fail = true;
    await addTagToRefetch(page);
    await expect(guardDialog(page)).toBeVisible();

    await guardDialog(page).getByRole('button', { name: '취소' }).click();
    await expect(guardDialog(page)).toBeHidden();
    // 예전엔 아무 안내 없이 옛 화면에 남았다 — 이제 서버와 다를 수 있음을 알린다
    const banner = staleBanner(page);
    await expect(banner).toBeVisible();

    // 편집을 끝내도(깨끗해져도) 저절로 떠나지 않는다 — 값 되돌리기·저장 직후에 엉뚱하게 떠나지 않게
    await page.getByRole('button', { name: '취소' }).click();
    await page.waitForTimeout(300);
    await expect(page).toHaveURL(/\/data\/datasets\/\d+$/);

    await banner.getByRole('button', { name: '목록으로' }).click();
    await expect(page).toHaveURL(/\/data\/datasets$/);
  });

  test('낡은 데이터 안내의 「다시 불러오기」가 성공하면 안내가 사라진다 (리뷰 후속)', async ({ authenticatedPage: page }) => {
    const control = await openDetail(page);
    await startEditing(page);

    control.fail = true;
    await addTagToRefetch(page);
    await guardDialog(page).getByRole('button', { name: '취소' }).click();
    const banner = staleBanner(page);
    await expect(banner).toBeVisible();

    // 다시 불러오기가 또 실패하면 그 사실을 알린다(실패 알림은 구간당 한 번이라 따로 알려야 한다)
    await banner.getByRole('button', { name: '다시 불러오기' }).click();
    await expect(page.getByText('데이터셋을 다시 불러오지 못했습니다')).toBeVisible();
    await expect(banner).toBeVisible();

    control.fail = false;
    await expect(banner.getByRole('button', { name: '다시 불러오기' })).not.toHaveAttribute('aria-disabled', 'true');
    await banner.getByRole('button', { name: '다시 불러오기' }).click();
    await expect(banner).toBeHidden();
    // 편집 중이던 값은 그대로다
    await expect(page.getByLabel('설명')).toHaveValue('고친 설명');
  });

  test('재조회가 404 면 삭제 안내를 띄우고 「다시 불러오기」는 두지 않는다 (리뷰 후속)', async ({ authenticatedPage: page }) => {
    await openDetail(page);
    await startEditing(page);

    // 기본 상세 모킹보다 나중에 등록해 먼저 받는다 — 재조회부터 404
    await page.route(
      (url) => url.pathname === '/api/v1/datasets/1',
      (route) =>
        route.request().method() === 'GET'
          ? route.fulfill({
              status: 404,
              contentType: 'application/json',
              body: JSON.stringify({ status: 404, message: '데이터셋을 찾을 수 없습니다' }),
            })
          : route.fallback(),
    );
    await addTagToRefetch(page);
    await guardDialog(page).getByRole('button', { name: '취소' }).click();

    const banner = page.getByRole('status').filter({ hasText: '이 데이터셋은 삭제되었거나 볼 수 없게 되었습니다' });
    await expect(banner).toBeVisible();
    await expect(banner.getByRole('button', { name: '다시 불러오기' })).toHaveCount(0);
    await expect(banner.getByRole('button', { name: '목록으로' })).toBeVisible();
  });

  test('깨끗한 상태에서 재조회가 실패하면 지금처럼 바로 목록으로 간다', async ({ authenticatedPage: page }) => {
    const control = await openDetail(page);

    control.fail = true;
    await addTagToRefetch(page);

    await expect(page).toHaveURL(/\/data\/datasets$/);
  });
});
