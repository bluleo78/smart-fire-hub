import type { Page } from '@playwright/test';

import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks, setupDatasetMocks } from '../../fixtures/dataset.fixture';
import { holdApi, submitAndRetryWhilePending } from '../../fixtures/hold-api';

/**
 * 데이터 화면 폼 제출 중 포커스 유지 E2E (WD-637, WD-572 후속)
 *
 * 결함: 데이터셋 생성·복제·행 추가/편집·필드·기본 정보의 제출 버튼이 disabled={isSubmitting} 이라,
 * Enter 로 제출하면 포커스가 있던 버튼이 비활성화되며 포커스가 body 로 떨어졌다(WCAG 2.4.3) — 실패해도 돌아오지 않는다.
 * 이제 PendingSubmitButton(aria-disabled 로 잠금) + useSubmitGuard(진행 중 재제출 무시)로 포커스를 버튼에 두고 요청은 한 번만 보낸다.
 *
 * 공통 흐름: 응답을 붙잡은 채 Enter 제출 → 진행 중 포커스 유지·aria-disabled·두 번째 Enter·requestSubmit 무시 → 응답을 풀면 실패 뒤에도 포커스 유지.
 */

/** 서버 오류 응답 본문 — handleApiError 가 토스트로 보여 준다 */
const SERVER_ERROR = { status: 500, message: '서버 오류가 발생했습니다.' };

/** 포커스가 열린 다이얼로그 안에 있는가 — 실패 뒤 포커스가 다이얼로그 밖(body)으로 빠지지 않았는지 본다 */
async function focusInsideDialog(page: Page) {
  return page.evaluate(() => !!document.querySelector('[role="dialog"]')?.contains(document.activeElement));
}

// 쓰기 흐름이라 데이터셋 쓰기 권한을 준다(WD-144)
test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

test.describe('데이터셋 생성 — 제출 중 포커스 (WD-637)', () => {
  test('Enter 로 제출해 실패해도 포커스가 「만들기」에 남고 요청은 한 번만 간다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    const create = await holdApi(page, 'POST', '/api/v1/datasets', 500, SERVER_ERROR);

    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('포커스 데이터셋');
    await page.getByLabel('테이블명').fill('focus_dataset');
    await page.getByPlaceholder('예: user_id').fill('col_name');
    const submit = page.getByRole('button', { name: '만들기' });
    await expect(submit).toBeEnabled();

    await submitAndRetryWhilePending(page, submit, page.getByRole('button', { name: '만드는 중...' }), create);
    create.release();

    await expect(submit).toBeFocused();
    await expect(submit).not.toHaveAttribute('aria-disabled', 'true');
  });
});

test.describe('데이터셋 상세 다이얼로그 — 제출 중 포커스 (WD-637)', () => {
  const datasetDetail = createDatasetDetail({
    id: 1,
    rowCount: 2,
    columns: [
      createColumn({ id: 1, columnName: 'id', displayName: 'ID', dataType: 'INTEGER', isPrimaryKey: true }),
      createColumn({
        id: 2,
        columnName: 'name',
        displayName: '이름',
        dataType: 'TEXT',
        isPrimaryKey: false,
        isNullable: false,
        columnOrder: 1,
      }),
    ],
  });

  /** 상세 화면 공통 모킹 — 행 추가에 쓸 PK·이름 컬럼을 가진 데이터셋(id 1) */
  async function setupDetail(page: Page) {
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/datasets/1', datasetDetail);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/1/data', {
      columns: datasetDetail.columns,
      rows: [{ id: 1, name: 'Alice' }],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
    });
  }

  /** 데이터 탭의 「행 추가」 다이얼로그를 열고 필수 칸을 채운다 */
  async function openAddRow(page: Page) {
    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
    await page.getByRole('tab', { name: '데이터' }).click();
    await page.getByRole('button', { name: /행 추가/ }).click();
    await expect(page.getByRole('dialog').getByRole('heading', { name: '행 추가' })).toBeVisible();
    await page.locator('#add-id').fill('3');
    await page.locator('#add-name').fill('Carol');
  }

  test('행 추가: Enter 로 제출해 실패해도 포커스가 다이얼로그의 「추가」에 남고 요청은 한 번만 간다', async ({
    authenticatedPage: page,
  }) => {
    await setupDetail(page);
    const add = await holdApi(page, 'POST', '/api/v1/datasets/1/data/rows', 500, SERVER_ERROR);
    await openAddRow(page);
    const dialog = page.getByRole('dialog');

    await submitAndRetryWhilePending(
      page,
      dialog.getByRole('button', { name: '추가', exact: true }),
      dialog.getByRole('button', { name: '추가 중...' }),
      add,
    );
    add.release();

    await expect(page.getByText('서버 오류가 발생했습니다.')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '추가', exact: true })).toBeFocused();
    expect(await focusInsideDialog(page)).toBe(true);
  });

  test('행 추가: 성공하면 다이얼로그가 닫히고 포커스가 「행 추가」 버튼으로 돌아간다', async ({
    authenticatedPage: page,
  }) => {
    await setupDetail(page);
    const add = await holdApi(page, 'POST', '/api/v1/datasets/1/data/rows', 200, { id: 3, name: 'Carol' });
    await openAddRow(page);
    const dialog = page.getByRole('dialog');

    await submitAndRetryWhilePending(
      page,
      dialog.getByRole('button', { name: '추가', exact: true }),
      dialog.getByRole('button', { name: '추가 중...' }),
      add,
    );
    add.release();

    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /행 추가/ })).toBeFocused();
  });

  test('행 편집: Enter 로 저장해 실패해도 포커스가 다이얼로그의 「저장」에 남고 요청은 한 번만 간다 (WD-683)', async ({
    authenticatedPage: page,
  }) => {
    await setupDetail(page);
    const put = await holdApi(page, 'PUT', '/api/v1/datasets/1/data/rows/1', 500, SERVER_ERROR);
    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
    await page.getByRole('tab', { name: '데이터' }).click();
    // 행 더블클릭 → EditRowDialog(행 편집 (ID: 1))
    await page.getByRole('cell', { name: 'Alice' }).dblclick();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: /행 편집 \(ID: 1\)/ })).toBeVisible();
    await page.locator('#edit-name').fill('Alice Edited');

    await submitAndRetryWhilePending(
      page,
      dialog.getByRole('button', { name: '저장', exact: true }),
      dialog.getByRole('button', { name: '저장 중...' }),
      put,
    );
    put.release();

    await expect(page.getByText('서버 오류가 발생했습니다.')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '저장', exact: true })).toBeFocused();
    expect(await focusInsideDialog(page)).toBe(true);
    // 바꾼 칸만 실려 갔는지 — 잠금이 제출 내용을 바꾸지 않는다
    expect(JSON.parse(put.requests[0] ?? '{}')).toEqual({ data: { name: 'Alice Edited' } });
  });

  test('필드 추가: Enter 로 제출해 실패해도 포커스가 다이얼로그의 「추가」에 남고 요청은 한 번만 간다', async ({
    authenticatedPage: page,
  }) => {
    await setupDetail(page);
    await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);
    const add = await holdApi(page, 'POST', '/api/v1/datasets/1/columns', 500, SERVER_ERROR);
    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
    await page.getByRole('tab', { name: '필드' }).click();
    await page.getByRole('button', { name: '필드 추가' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('필드명 *').fill('score');

    await submitAndRetryWhilePending(
      page,
      dialog.getByRole('button', { name: '추가', exact: true }),
      dialog.getByRole('button', { name: '추가 중...' }),
      add,
    );
    add.release();

    await expect(page.getByText('서버 오류가 발생했습니다.')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '추가', exact: true })).toBeFocused();
    expect(await focusInsideDialog(page)).toBe(true);
  });

  test('복제: Enter 로 제출해 실패해도 포커스가 다이얼로그의 「복제」에 남고 요청은 한 번만 간다', async ({
    authenticatedPage: page,
  }) => {
    await setupDetail(page);
    const clone = await holdApi(page, 'POST', '/api/v1/datasets/1/clone', 500, SERVER_ERROR);
    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
    await page.getByRole('button', { name: '복제' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: '데이터셋 복제' })).toBeVisible();

    await submitAndRetryWhilePending(
      page,
      dialog.getByRole('button', { name: '복제', exact: true }),
      dialog.getByRole('button', { name: '복제 중...' }),
      clone,
    );
    clone.release();

    await expect(page.getByText('서버 오류가 발생했습니다.')).toBeVisible();
    await expect(dialog.getByRole('button', { name: '복제', exact: true })).toBeFocused();
    expect(await focusInsideDialog(page)).toBe(true);
  });

  test('기본 정보: Enter 로 저장해 실패해도 포커스가 「저장」에 남고 요청은 한 번만 간다', async ({
    authenticatedPage: page,
  }) => {
    await setupDetail(page);
    const put = await holdApi(page, 'PUT', '/api/v1/datasets/1', 500, SERVER_ERROR);
    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByLabel('설명').fill('고친 설명');
    const save = page.getByRole('button', { name: '저장', exact: true });

    // 저장 버튼은 진행 중에도 라벨이 「저장」 그대로다
    await submitAndRetryWhilePending(page, save, save, put);
    put.release();

    await expect(page.getByText('서버 오류가 발생했습니다.')).toBeVisible();
    await expect(save).toBeFocused();
  });
});
