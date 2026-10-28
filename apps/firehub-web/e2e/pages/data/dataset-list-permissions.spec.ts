import type { Page } from '@playwright/test';

import { createCategories } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 목록·카테고리 관리·데이터셋 생성 — 권한별 쓰기 진입점 노출 (WD-144)
 *
 * 조회 권한만 있는 사용자(dataset:read)에게도 「데이터셋 추가」·행 휴지통·「새 카테고리」·카테고리 편집/삭제가 보였고,
 * 누르면 다 채운 뒤 403 을 받았다. 생성 주소(/data/datasets/new)도 가드가 없어 양식을 다 채운 뒤 거절됐다.
 * 서버가 요구하는 권한(DatasetController·DatasetCategoryController — 생성·수정 dataset:write, 삭제 dataset:delete)과
 * 같은 기준으로 보이는지 조합별로 확인한다.
 *
 * 부재 단언이 공허해지지 않도록 모든 경우가 같은 준비 절차를 탄다: ① 권한 응답을 기다리고 ② 목록 값이 그려진 것을 확인한 뒤
 * ③ 한 프레임 쉬고 판정한다(member-add.spec 선례). 쓰기 권한이 있는 대조군이 같은 절차에서 버튼을 보이므로
 * 절차 자체가 버튼을 가리지 않음이 증명된다.
 */

const READ_ONLY = ['dataset:read', 'data:read'];
const WRITE_ONLY = [...READ_ONLY, 'dataset:write'];
const DELETE_ONLY = [...READ_ONLY, 'dataset:delete'];
const WRITE_AND_DELETE = [...READ_ONLY, 'dataset:write', 'dataset:delete'];

/** 권한 응답이 도착한 뒤 경로로 이동한다 — 권한이 오기 전의 "숨김"을 결과로 착각하지 않기 위함. */
async function gotoWithPermissions(page: Page, path: string) {
  const permissionsLoaded = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/auth/me/permissions');
  await page.goto(path);
  await permissionsLoaded;
}

/** 응답 처리 후 한 프레임 뒤(리렌더 반영) — 「없다」 단언 직전에 둔다. */
async function nextFrame(page: Page) {
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));
}

/** 데이터셋 목록을 열고 행 값이 그려질 때까지 기다린다. */
async function openDatasetList(page: Page, permissions: string[]) {
  await setupDatasetMocks(page);
  await grantDatasetPermissions(page, permissions);
  await gotoWithPermissions(page, '/data/datasets');
  await expect(page.getByRole('heading', { name: '데이터셋 관리' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '데이터셋 1', exact: true })).toBeVisible();
  await nextFrame(page);
}

/** 빈 데이터셋 목록(필터 없음)을 열고 빈 안내가 그려질 때까지 기다린다. */
async function openEmptyDatasetList(page: Page, permissions: string[]) {
  await setupDatasetMocks(page);
  await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]));
  await grantDatasetPermissions(page, permissions);
  await gotoWithPermissions(page, '/data/datasets');
  await expect(page.getByText('데이터셋이 없습니다.')).toBeVisible();
  await nextFrame(page);
}

/** 카테고리 관리를 열고 카테고리 이름이 그려질 때까지 기다린다. */
async function openCategories(page: Page, permissions: string[]) {
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await grantDatasetPermissions(page, permissions);
  await gotoWithPermissions(page, '/data/categories');
  await expect(page.getByRole('heading', { name: '카테고리 관리' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '소방 데이터', exact: true })).toBeVisible();
  await nextFrame(page);
}

test.describe('데이터셋 목록 — 권한별 추가·삭제 노출 (WD-144)', () => {
  test('조회 전용이면 「데이터셋 추가」와 행 휴지통이 없고 조회 동작(미리보기)은 남는다', async ({ authenticatedPage: page }) => {
    await openDatasetList(page, READ_ONLY);

    await expect(page.getByRole('button', { name: '데이터셋 추가' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toHaveCount(0);
    // 조회 동작은 그대로 — 행마다 미리보기 버튼이 있다
    await expect(page.getByRole('button', { name: '미리보기' })).toHaveCount(5);
  });

  test('쓰기·삭제 권한이 있으면 같은 준비 후 「데이터셋 추가」와 행마다 휴지통이 보인다(대조군)', async ({
    authenticatedPage: page,
  }) => {
    await openDatasetList(page, WRITE_AND_DELETE);

    await expect(page.getByRole('button', { name: '데이터셋 추가' })).toHaveCount(1);
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toHaveCount(5);
  });

  test('dataset:write 만 있으면 「데이터셋 추가」는 보이고 휴지통은 없다', async ({ authenticatedPage: page }) => {
    await openDatasetList(page, WRITE_ONLY);

    await expect(page.getByRole('button', { name: '데이터셋 추가' })).toHaveCount(1);
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toHaveCount(0);
  });

  test('dataset:delete 만 있으면 휴지통은 보이고 「데이터셋 추가」는 없다', async ({ authenticatedPage: page }) => {
    await openDatasetList(page, DELETE_ONLY);

    await expect(page.getByRole('button', { name: '삭제', exact: true })).toHaveCount(5);
    await expect(page.getByRole('button', { name: '데이터셋 추가' })).toHaveCount(0);
  });

  test('빈 목록: 조회 전용이면 안내 문구만 있고 「새 데이터셋 만들기」가 없다', async ({ authenticatedPage: page }) => {
    await openEmptyDatasetList(page, READ_ONLY);

    await expect(page.getByRole('button', { name: '새 데이터셋 만들기' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '데이터셋 추가' })).toHaveCount(0);
  });

  test('빈 목록: dataset:write 가 있으면 같은 준비 후 「새 데이터셋 만들기」가 보인다(대조군)', async ({
    authenticatedPage: page,
  }) => {
    await openEmptyDatasetList(page, WRITE_ONLY);

    await expect(page.getByRole('button', { name: '새 데이터셋 만들기' })).toHaveCount(1);
  });
});

test.describe('데이터셋 생성 주소 직접 진입 — 권한 가드 (WD-144)', () => {
  test('조회 전용이면 레이아웃 안에서 권한 안내를 보이고 양식을 그리지 않는다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await grantDatasetPermissions(page, READ_ONLY);
    await gotoWithPermissions(page, '/data/datasets/new?storageType=TABLE&originType=SOURCE');

    await expect(page.getByTestId('screen-forbidden')).toBeVisible();
    await expect(page.getByTestId('screen-forbidden')).toContainText('이 화면을 볼 권한이 없습니다');
    // 레이아웃(사이드바) 안에 머문다 — 홈으로 튕기지 않는다
    await expect(page).toHaveURL(/\/data\/datasets\/new/);
    await expect(page.getByRole('link', { name: '데이터셋' }).first()).toBeVisible();
    await expect(page.getByRole('heading', { name: '데이터셋 생성' })).toHaveCount(0);
    await expect(page.getByLabel('데이터셋 이름')).toHaveCount(0);
  });

  test('dataset:delete 만으로는 열리지 않는다 — 생성은 dataset:write 기준', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await grantDatasetPermissions(page, DELETE_ONLY);
    await gotoWithPermissions(page, '/data/datasets/new');

    await expect(page.getByTestId('screen-forbidden')).toBeVisible();
    await expect(page.getByLabel('데이터셋 이름')).toHaveCount(0);
  });

  test('dataset:write 가 있으면 같은 준비 후 생성 양식이 열린다(대조군)', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await grantDatasetPermissions(page, WRITE_ONLY);
    await gotoWithPermissions(page, '/data/datasets/new?storageType=TABLE&originType=SOURCE');

    await expect(page.getByRole('heading', { name: '데이터셋 생성' })).toBeVisible();
    await expect(page.getByLabel('데이터셋 이름')).toBeVisible();
    await expect(page.getByTestId('screen-forbidden')).toHaveCount(0);
  });

  test('권한 조회가 실패하면 「권한 없음」이 아니라 불러오기 실패를 알린다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', { message: 'error' }, { status: 500 });
    await page.goto('/data/datasets/new');

    await expect(page.getByTestId('screen-permission-error')).toBeVisible();
    await expect(page.getByTestId('screen-forbidden')).toHaveCount(0);
    await expect(page.getByLabel('데이터셋 이름')).toHaveCount(0);
  });
});

test.describe('홈 퀵 액션 — 데이터셋 생성 바로가기 (WD-144)', () => {
  test('조회 전용이면 「새 데이터셋」 퀵 액션이 없고 다른 퀵 액션은 남는다', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page, READ_ONLY);
    await gotoWithPermissions(page, '/');
    // 같은 퀵 액션 묶음의 다른 버튼이 그려진 뒤 판정 — 묶음이 안 그려져서 없는 것과 구분한다
    // (「파이프라인 목록」은 pipeline:read 가 있어야 보여 기준으로 쓰지 않는다 — WD-272)
    await expect(page.getByRole('button', { name: /SQL 편집기/ })).toBeVisible();
    await nextFrame(page);

    await expect(page.getByRole('button', { name: /새 데이터셋/ })).toHaveCount(0);
  });

  test('dataset:write 가 있으면 같은 준비 후 「새 데이터셋」 퀵 액션이 보인다(대조군)', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page, WRITE_ONLY);
    await gotoWithPermissions(page, '/');
    await expect(page.getByRole('button', { name: /SQL 편집기/ })).toBeVisible();
    await nextFrame(page);

    await expect(page.getByRole('button', { name: /새 데이터셋/ })).toHaveCount(1);
  });

  // 홈 기본 모킹(setupHomeMocks)의 데이터셋 목록은 비어 있어 최근 데이터셋 카드가 빈 안내를 보인다
  test('빈 최근 데이터셋 카드: 조회 전용이면 안내만 있고 「만들기」 링크가 없다', async ({ authenticatedPage: page }) => {
    await grantDatasetPermissions(page, READ_ONLY);
    await gotoWithPermissions(page, '/');
    await expect(page.getByText('데이터셋이 없습니다.')).toBeVisible();
    await nextFrame(page);

    // 홈에는 대시보드 「만들기」 링크도 있어 이름이 아니라 생성 화면 주소로 고른다
    await expect(page.locator('a[href="/data/datasets/new"]')).toHaveCount(0);
    // 다른 카드의 「만들기」 링크는 남는다 — 링크가 통째로 안 그려진 것이 아님을 확인
    await expect(page.getByRole('link', { name: '만들기' })).toHaveCount(1);
  });

  test('빈 최근 데이터셋 카드: dataset:write 가 있으면 같은 준비 후 「만들기」 링크가 생성 화면을 가리킨다(대조군)', async ({
    authenticatedPage: page,
  }) => {
    await grantDatasetPermissions(page, WRITE_ONLY);
    await gotoWithPermissions(page, '/');
    await expect(page.getByText('데이터셋이 없습니다.')).toBeVisible();
    await nextFrame(page);

    await expect(page.locator('a[href="/data/datasets/new"]')).toHaveText('만들기');
  });
});

test.describe('카테고리 관리 — 권한별 생성·편집·삭제 노출 (WD-144)', () => {
  test('조회 전용이면 「새 카테고리」·편집·삭제·「작업」 열이 없다', async ({ authenticatedPage: page }) => {
    await openCategories(page, READ_ONLY);

    await expect(page.getByRole('button', { name: '새 카테고리' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /편집$/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /삭제$/ })).toHaveCount(0);
    await expect(page.getByRole('columnheader', { name: '작업' })).toHaveCount(0);
  });

  test('쓰기·삭제 권한이 있으면 같은 준비 후 모두 보인다(대조군)', async ({ authenticatedPage: page }) => {
    await openCategories(page, WRITE_AND_DELETE);

    await expect(page.getByRole('button', { name: '새 카테고리' })).toHaveCount(1);
    await expect(page.getByRole('button', { name: /편집$/ })).toHaveCount(3);
    await expect(page.getByRole('button', { name: /삭제$/ })).toHaveCount(3);
    await expect(page.getByRole('columnheader', { name: '작업' })).toHaveCount(1);
  });

  test('dataset:write 만 있으면 「새 카테고리」·편집만 보이고 삭제는 없다(서버 DELETE 는 dataset:delete)', async ({
    authenticatedPage: page,
  }) => {
    await openCategories(page, WRITE_ONLY);

    await expect(page.getByRole('button', { name: '새 카테고리' })).toHaveCount(1);
    await expect(page.getByRole('button', { name: '소방 데이터 편집' })).toBeVisible();
    await expect(page.getByRole('button', { name: /삭제$/ })).toHaveCount(0);
  });

  test('dataset:delete 만 있으면 삭제만 보이고 「새 카테고리」·편집은 없다', async ({ authenticatedPage: page }) => {
    await openCategories(page, DELETE_ONLY);

    await expect(page.getByRole('button', { name: '소방 데이터 삭제' })).toBeVisible();
    await expect(page.getByRole('button', { name: '새 카테고리' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /편집$/ })).toHaveCount(0);
  });
});
