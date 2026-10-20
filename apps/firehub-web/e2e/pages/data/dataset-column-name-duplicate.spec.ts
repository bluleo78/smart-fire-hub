import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';

/**
 * 필드명 중복·NULL 허용 사전 차단 E2E (WD-99)
 *
 * 흔한 실수(같은 이름 필드, 데이터가 있는 데이터셋에 NULL 불허 필드 추가)를 예전엔 화면이 막지 않아
 * 서버의 영어 원문(「Data integrity violation: duplicate entry」 등)만 떴다. 이제 화면이 먼저 한국어로 안내하고
 * 요청 자체를 보내지 않아야 한다.
 */

// 문구는 화면 라벨을 따른다 — 생성 화면(라벨 「칼럼명」)과 필드 추가·수정 다이얼로그(라벨 「필드명」).
const DUPLICATE_MESSAGE = '이미 있는 칼럼명입니다';
const FIELD_DUPLICATE_MESSAGE = '이미 있는 필드명입니다';
const NULLABLE_LOCK_MESSAGE = '데이터가 있는 데이터셋에는 NULL 허용을 끈 필드를 추가할 수 없습니다.';

/** title·body 두 필드를 가진 데이터셋 상세 — rowCount 로 데이터 유무를 바꾼다. */
function datasetWith(rowCount: number) {
  return createDatasetDetail({
    id: 5,
    rowCount,
    columns: [
      createColumn({ id: 1, columnName: 'title', displayName: '제목', dataType: 'TEXT', columnOrder: 0 }),
      createColumn({ id: 2, columnName: 'body', displayName: '본문', dataType: 'TEXT', columnOrder: 1 }),
    ],
  });
}

/** 데이터셋 상세 → 필드 탭까지 띄우는 목업. 데이터가 있으면 필드 통계도 불러오므로 빈 통계를 준다. */
async function openColumnsTab(page: import('@playwright/test').Page, rowCount: number) {
  await mockApi(page, 'GET', '/api/v1/datasets/5', datasetWith(rowCount));
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/5/queries', createPageResponse([]));
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await mockApi(page, 'GET', '/api/v1/datasets/5/stats', []);

  await page.goto('/data/datasets/5');
  await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible({ timeout: 10000 });
  await page.getByRole('tab', { name: '필드' }).click();
  await expect(page.getByRole('heading', { name: /필드 목록/ })).toBeVisible({ timeout: 10000 });
}

test.describe('데이터셋 생성 — 필드 목록 이름 중복 (WD-99)', () => {
  test('같은 이름 칼럼 두 개는 뒤 칸에 안내가 뜨고 요청을 보내지 않으며, 고치면 생성 요청을 보낸다', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetMocks(page);
    const capture = await mockApi(page, 'POST', '/api/v1/datasets', { id: 99 }, { capture: true });

    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('중복 칼럼');
    await page.getByLabel('테이블명').fill('dup_columns');
    await page.getByRole('button', { name: '칼럼 추가' }).click();
    const columnInputs = page.getByPlaceholder('예: user_id');
    await columnInputs.nth(0).fill('code');
    await columnInputs.nth(1).fill('code');

    await page.getByRole('button', { name: '생성' }).click();

    // 안내는 뒤에 나온(두 번째) 칸 하나에만 뜨고, 요청은 나가지 않는다.
    await expect(page.getByText(DUPLICATE_MESSAGE)).toHaveCount(1);
    const secondCard = page.locator('[id="columns.1.columnName"]').locator('xpath=..');
    await expect(secondCard.getByText(DUPLICATE_MESSAGE)).toBeVisible();
    await page.waitForTimeout(300);
    expect(capture.requests).toHaveLength(0);

    // 이름을 고치면 안내 없이 생성 요청을 보낸다(대조군 — 중복 검사가 과하게 막지 않음).
    await columnInputs.nth(1).fill('name');
    await page.getByRole('button', { name: '생성' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      columns: [
        expect.objectContaining({ columnName: 'code' }),
        expect.objectContaining({ columnName: 'name' }),
      ],
    });
    await expect(page.getByText(DUPLICATE_MESSAGE)).toHaveCount(0);
  });
});

test.describe('필드 탭 — 필드 추가·수정 이름 중복과 NULL 허용 잠금 (WD-99)', () => {
  test('필드 추가 — 기존 필드명(title)은 안내가 뜨고 POST 요청을 보내지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const capture = await mockApi(page, 'POST', '/api/v1/datasets/5/columns', {}, { capture: true });
    await openColumnsTab(page, 100);

    await page.getByRole('button', { name: '필드 추가' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await dialog.getByLabel('필드명 *').fill('title');
    await dialog.getByRole('button', { name: '추가' }).click();

    await expect(dialog.getByText(FIELD_DUPLICATE_MESSAGE)).toBeVisible({ timeout: 5000 });
    await expect(dialog).toBeVisible();
    await page.waitForTimeout(300);
    expect(capture.requests).toHaveLength(0);
  });

  test('필드 수정 — 다른 기존 필드명으로 바꾸면 안내가 뜨고 PUT 요청을 보내지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const capture = await mockApi(page, 'PUT', '/api/v1/datasets/5/columns/2', {}, { capture: true });
    // 데이터가 없어야 필드명 입력이 잠기지 않는다.
    await openColumnsTab(page, 0);

    const bodyRow = page.getByRole('row').filter({ hasText: 'body' }).first();
    await bodyRow.getByRole('button', { name: '컬럼 편집' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible({ timeout: 5000 });
    await dialog.getByLabel('필드명 *').fill('title');
    await dialog.getByRole('button', { name: '수정' }).click();

    await expect(dialog.getByText(FIELD_DUPLICATE_MESSAGE)).toBeVisible({ timeout: 5000 });
    await page.waitForTimeout(300);
    expect(capture.requests).toHaveLength(0);
  });

  test('필드 수정 — 자기 이름을 그대로 두면 중복이 아니다(PUT 을 보낸다)', async ({
    authenticatedPage: page,
  }) => {
    const capture = await mockApi(
      page,
      'PUT',
      '/api/v1/datasets/5/columns/2',
      {},
      { capture: true },
    );
    await openColumnsTab(page, 0);

    const bodyRow = page.getByRole('row').filter({ hasText: 'body' }).first();
    await bodyRow.getByRole('button', { name: '컬럼 편집' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible({ timeout: 5000 });
    await dialog.getByLabel('표시명').fill('본문 수정');
    await dialog.getByRole('button', { name: '수정' }).click();

    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ columnName: 'body', displayName: '본문 수정' });
    await expect(page.getByText(FIELD_DUPLICATE_MESSAGE)).toHaveCount(0);
  });

  test('데이터가 있으면 필드 추가의 NULL 허용이 켜진 채 잠기고 이유가 보인다', async ({
    authenticatedPage: page,
  }) => {
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/5/columns',
      { id: 10, columnName: 'memo', displayName: null, dataType: 'TEXT', maxLength: null,
        isNullable: true, isIndexed: false, isPrimaryKey: false, description: null, columnOrder: 2 },
      { capture: true },
    );
    await openColumnsTab(page, 100);

    await page.getByRole('button', { name: '필드 추가' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    const nullable = dialog.getByRole('checkbox', { name: 'NULL 허용' });
    await expect(nullable).toBeChecked();
    await expect(nullable).toBeDisabled();
    await expect(dialog.getByText(NULLABLE_LOCK_MESSAGE)).toBeVisible();

    // 잠긴 채로 추가하면 NULL 허용으로 보낸다.
    await dialog.getByLabel('필드명 *').fill('memo');
    await dialog.getByRole('button', { name: '추가' }).click();
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({ columnName: 'memo', isNullable: true });
  });

  test('데이터가 없으면 필드 추가의 NULL 허용을 끌 수 있고 잠금 안내가 없다', async ({
    authenticatedPage: page,
  }) => {
    await openColumnsTab(page, 0);

    await page.getByRole('button', { name: '필드 추가' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    const nullable = dialog.getByRole('checkbox', { name: 'NULL 허용' });
    await expect(nullable).toBeEnabled();
    await nullable.click();
    await expect(nullable).not.toBeChecked();
    await expect(dialog.getByText(NULLABLE_LOCK_MESSAGE)).toHaveCount(0);
  });
});
