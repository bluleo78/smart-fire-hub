import { createDatasetDetail } from '../factories/dataset.factory';
import { createPageResponse, mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupDatasetDetailMocks, setupDatasetMocks } from '../fixtures/dataset.fixture';

/**
 * 데이터셋 CRUD 플로우 E2E 테스트
 * - 목록 → 상세 이동, 생성 플로우, 삭제 플로우를 통합 검증한다.
 */
test.describe('데이터셋 CRUD 플로우', () => {
  test('목록 페이지에서 행 클릭 시 상세 페이지로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    // 목록 API 모킹
    await setupDatasetMocks(page);

    // 상세 페이지 API 모킹 (목록에서 클릭 후 이동할 페이지)
    await setupDatasetDetailMocks(page, 1);
    const { createCategories } = await import('../factories/dataset.factory');
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', ['sample', 'test']);

    await page.goto('/data/datasets');

    // 첫 번째 데이터셋 이름 셀 클릭 (행 전체 클릭 시 상세 페이지로 이동)
    await page.getByRole('cell', { name: '데이터셋 1', exact: true }).click();

    // 상세 페이지로 이동 확인
    await expect(page).toHaveURL(/\/data\/datasets\/1/);

    // 상세 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 팩토리 데이터 검증 — 태그와 테이블명이 상세 페이지에 렌더링되는지 확인
    // createDatasetDetail의 기본값: tags: ['테스트', '샘플'], tableName: 'test_dataset'
    await expect(page.getByText('테스트').first()).toBeVisible();
    await expect(page.getByText('샘플').first()).toBeVisible();
    await expect(page.getByText('test_dataset').first()).toBeVisible();
  });

  test('데이터셋 추가 버튼 → 생성 페이지 → 취소 → 목록으로 돌아온다', async ({
    authenticatedPage: page,
  }) => {
    // 목록 API 모킹
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    // 데이터셋 추가 버튼 클릭
    await page.getByRole('link', { name: /데이터셋 추가/ }).click();
    await expect(page).toHaveURL('/data/datasets/new');

    // 취소 버튼 클릭 → 목록으로 복귀
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page).toHaveURL('/data/datasets');
  });

  test('데이터셋 생성 후 상세 페이지로 자동 이동한다', async ({ authenticatedPage: page }) => {
    // 목록 및 카테고리 모킹
    await setupDatasetMocks(page);

    // POST /api/v1/datasets 캡처 — 폼 입력값이 올바른 payload로 전송되는지 검증
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets',
      { id: 42, name: '신규 데이터셋' },
      { capture: true },
    );

    // 상세 페이지 모킹
    const detail = createDatasetDetail({ id: 42, name: '신규 데이터셋', tableName: 'new_dataset' });
    await mockApi(page, 'GET', '/api/v1/datasets/42', detail);
    await mockApi(page, 'GET', '/api/v1/datasets/42/data', {
      columns: detail.columns,
      rows: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });
    await mockApi(page, 'GET', '/api/v1/datasets/42/stats', []);
    await mockApi(page, 'GET', '/api/v1/datasets/42/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets/new');

    // 필수 필드 입력
    await page.getByLabel('데이터셋 이름').fill('신규 데이터셋');
    await page.getByLabel('테이블명').fill('new_dataset');

    // SchemaBuilder의 첫 번째 칼럼명 입력 (필수, 영문 소문자로 시작)
    await page.getByPlaceholder('예: user_id').fill('col_name');

    // 생성 버튼 클릭
    await page.getByRole('button', { name: '생성' }).click();

    // POST API payload 검증 — 입력한 값이 올바르게 전송되었는지 확인
    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '신규 데이터셋',
      tableName: 'new_dataset',
    });

    // 생성 후 상세 페이지로 이동 확인
    await expect(page).toHaveURL('/data/datasets/42');
  });

  test('목록 페이지에서 삭제 버튼 클릭 시 확인 다이얼로그가 열린다', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetMocks(page);

    // DELETE /api/v1/datasets/1 캡처 — 삭제 확인 클릭 시 API 호출 검증
    const deleteCapture = await mockApi(
      page,
      'DELETE',
      '/api/v1/datasets/1',
      {},
      { capture: true },
    );

    await page.goto('/data/datasets');

    // 첫 번째 행의 삭제 버튼 클릭 (aria-label="삭제")
    const deleteButtons = page.getByRole('button', { name: '삭제' });
    await deleteButtons.first().click();

    // 삭제 확인 다이얼로그가 열리는지 확인
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // 다이얼로그에서 삭제 확인 버튼 클릭 — API 호출 여부 검증
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();
    const req = await deleteCapture.waitForRequest();
    // DELETE 요청이 올바른 엔드포인트로 전송되었는지 확인
    expect(req.url.pathname).toBe('/api/v1/datasets/1');
  });
});
