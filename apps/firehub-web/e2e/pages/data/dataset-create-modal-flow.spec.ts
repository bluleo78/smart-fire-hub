import { createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 생성 2단계 모달 플로우 E2E 테스트
 *
 * 유형(저장방식/출처)이 폼 내부 Select에서 분리되어, 목록 페이지의 "데이터셋 추가" 버튼이
 * 2단계 모달(저장방식 → 출처)을 띄우고 선택 결과를 URL 쿼리로 전달하며 생성 폼으로 이동한다.
 *
 * 검증 범위 (입력 → API payload → UI 반영):
 * - 테이블 경로: 저장방식(테이블) → 출처(원본) → /new?storageType=TABLE&originType=SOURCE
 *   → 칼럼 정의 카드 + "테이블/원본" 유형 표시 → 폼 제출 시 storageType/originType payload 검증
 * - 문서 경로: 저장방식(문서) → (출처 단계 생략) → /new?storageType=DOCUMENT
 *   → 테이블명/칼럼 숨김 → 제출 시 storageType=DOCUMENT payload 검증
 */
test.describe('데이터셋 생성 — 2단계 유형 선택 모달 플로우', () => {
  test(
    '테이블 → 원본 선택 시 생성 폼으로 이동하고 payload에 storageType/originType이 전달된다',
    { tag: '@smoke' },
    async ({ authenticatedPage: page }) => {
      // 목록 페이지 기본 모킹 (카테고리, GET /datasets 목록, 태그)
      // — GET /datasets는 폼의 이름/테이블명 중복 검증 쿼리에도 재사용된다.
      await setupDatasetMocks(page);

      // POST /datasets payload 캡처 (GET 핸들러보다 나중에 등록 → 먼저 매칭, 비-POST는 fallback)
      const capture = await mockApi(
        page,
        'POST',
        '/api/v1/datasets',
        { id: 99, name: '신규 테이블셋' },
        { capture: true },
      );

      // 생성 성공 후 상세 페이지(/data/datasets/99)로 이동하므로 해당 API 모킹
      const detail99 = createDatasetDetail({ id: 99 });
      await mockApi(page, 'GET', '/api/v1/datasets/99', detail99);
      await mockApi(page, 'GET', '/api/v1/datasets/99/data', {
        columns: detail99.columns,
        rows: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      });
      await mockApi(page, 'GET', '/api/v1/datasets/99/stats', []);
      await mockApi(page, 'GET', '/api/v1/datasets/99/queries', createPageResponse([]));

      await page.goto('/data/datasets');
      await expect(page.getByRole('heading', { name: '데이터셋 관리' })).toBeVisible();

      // "데이터셋 추가" 클릭 → 1단계 모달 등장
      await page.getByRole('button', { name: '데이터셋 추가' }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByText('어떤 데이터셋을 만드시나요?')).toBeVisible();

      // 1단계: "테이블" 선택 → 2단계(출처 선택) 등장
      await dialog.getByRole('button', { name: '테이블' }).click();
      await expect(dialog.getByText('출처를 선택하세요')).toBeVisible();

      // 2단계: "원본" 선택 → 생성 폼으로 URL 쿼리와 함께 이동
      await dialog.getByRole('button', { name: '원본' }).click();
      await expect(page).toHaveURL('/data/datasets/new?storageType=TABLE&originType=SOURCE');

      // 테이블 유형이므로 칼럼 정의 카드가 표시된다
      await expect(page.getByRole('heading', { name: '칼럼 정의' })).toBeVisible();
      // 유형 표시 영역에 "테이블"·"원본" 두 배지가 노출된다
      const typeDisplay = page.getByText('데이터셋 유형').locator('..');
      await expect(typeDisplay.getByText('테이블', { exact: true })).toBeVisible();
      await expect(typeDisplay.getByText('원본', { exact: true })).toBeVisible();

      // 폼 입력 — 모킹된 5개 데이터셋과 충돌하지 않는 이름/테이블명 사용
      await page.getByLabel('데이터셋 이름').fill('신규 테이블셋');
      await page.getByLabel('테이블명').fill('new_table_ds');
      await page.getByPlaceholder('예: user_id').first().fill('col_a');

      await page.getByRole('button', { name: '생성' }).click();

      // 입력값 → API payload 검증: storageType/originType이 정확히 전달되는지 확인
      const req = await capture.waitForRequest();
      expect(req.payload).toMatchObject({
        name: '신규 테이블셋',
        tableName: 'new_table_ds',
        storageType: 'TABLE',
        originType: 'SOURCE',
        columns: [expect.objectContaining({ columnName: 'col_a' })],
      });

      // 성공 후 상세 페이지로 이동 확인
      await expect(page).toHaveURL('/data/datasets/99');
    },
  );

  test('문서 선택 시 출처 단계를 건너뛰고 테이블명/칼럼이 숨겨진 폼으로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await setupDatasetMocks(page);

    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets',
      { id: 100, name: '신규 문서셋' },
      { capture: true },
    );

    // 생성 성공 후 상세 페이지(/data/datasets/100) 이동에 필요한 API 모킹
    const detail100 = createDatasetDetail({ id: 100, storageType: 'DOCUMENT', originType: 'SOURCE', columns: [], rowCount: null });
    await mockApi(page, 'GET', '/api/v1/datasets/100', detail100);
    await mockApi(page, 'GET', '/api/v1/datasets/100/documents', []);
    await mockApi(page, 'GET', '/api/v1/datasets/100/data', {
      columns: [],
      rows: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });
    await mockApi(page, 'GET', '/api/v1/datasets/100/stats', []);
    await mockApi(page, 'GET', '/api/v1/datasets/100/queries', createPageResponse([]));

    await page.goto('/data/datasets');
    await expect(page.getByRole('heading', { name: '데이터셋 관리' })).toBeVisible();

    // "데이터셋 추가" → 1단계 모달 → "문서" 선택 (출처 단계 없이 바로 폼 이동)
    await page.getByRole('button', { name: '데이터셋 추가' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('어떤 데이터셋을 만드시나요?')).toBeVisible();
    await dialog.getByRole('button', { name: '문서' }).click();

    // storageType=DOCUMENT 쿼리로 생성 폼 이동
    await expect(page).toHaveURL(/\/data\/datasets\/new\?storageType=DOCUMENT/);

    // 문서 유형: 칼럼 정의 카드와 테이블명 입력이 숨겨진다
    await expect(page.getByRole('heading', { name: '칼럼 정의' })).toHaveCount(0);
    await expect(page.getByLabel('테이블명')).toHaveCount(0);
    // 유형 표시 영역에 "문서" 배지 노출
    const typeDisplay = page.getByText('데이터셋 유형').locator('..');
    await expect(typeDisplay.getByText('문서', { exact: true })).toBeVisible();

    await page.getByLabel('데이터셋 이름').fill('신규 문서셋');
    await page.getByRole('button', { name: '생성' }).click();

    // payload 검증: storageType=DOCUMENT, 칼럼 없음, tableName은 자동 생성(doc_<timestamp>)
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '신규 문서셋',
      storageType: 'DOCUMENT',
      columns: [],
    });
    expect((req.payload as { tableName: string }).tableName).toMatch(/^doc_\d+$/);

    await expect(page).toHaveURL('/data/datasets/100');
  });
});
