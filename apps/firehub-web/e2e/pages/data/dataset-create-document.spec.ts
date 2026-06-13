import { createDatasetDetail } from '../../factories/dataset.factory';
import { createCategories } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * DOCUMENT 유형 데이터셋 생성 E2E 테스트
 * - DOCUMENT 유형 선택 시 테이블명·칼럼 정의 카드 숨김
 * - tableName 자동 생성(doc_<timestamp>), columns: [] payload 검증
 */
test.describe('DOCUMENT 데이터셋 생성', () => {
  test(
    'DOCUMENT 유형 선택 시 칼럼 정의 카드가 숨겨지고 올바른 payload로 생성된다',
    { tag: '@smoke' },
    async ({ authenticatedPage: page }) => {
      // 카테고리 및 데이터셋 중복 검증 API 모킹
      await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
      // useDatasets(search/tablename dedup 검증)가 호출하는 GET /api/v1/datasets 모킹
      await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]));

      // POST /api/v1/datasets payload 캡처
      const detail = createDatasetDetail({ id: 10 });
      const capture = await mockApi(
        page,
        'POST',
        '/api/v1/datasets',
        { id: 10, name: '문서 데이터셋' },
        { capture: true },
      );

      // 생성 성공 후 상세 페이지 이동에 필요한 API 모킹 (네비게이션 오류 방지)
      await mockApi(page, 'GET', '/api/v1/datasets/10', detail);
      await mockApi(page, 'GET', '/api/v1/datasets/10/data', {
        columns: detail.columns,
        rows: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      });
      await mockApi(page, 'GET', '/api/v1/datasets/10/stats', []);
      await mockApi(page, 'GET', '/api/v1/datasets/10/queries', createPageResponse([]));
      await mockApi(page, 'GET', '/api/v1/datasets/tags', ['sample']);

      await page.goto('/data/datasets/new');

      // 데이터셋 이름 입력
      await page.getByLabel('데이터셋 이름').fill('문서 데이터셋');

      // 데이터셋 유형을 "문서"로 변경 — SelectTrigger는 role="combobox" 로 렌더링
      await page.getByRole('combobox').filter({ hasText: '원본' }).click();
      await page.getByRole('option', { name: '문서' }).click();

      // 칼럼 정의 카드가 사라지는지 확인 (DOCUMENT는 동적 칼럼 없음)
      await expect(page.getByRole('heading', { name: '칼럼 정의' })).not.toBeVisible();

      // 생성 버튼 클릭
      await page.getByRole('button', { name: '생성' }).click();

      // POST payload 검증
      const req = await capture.waitForRequest();
      expect(req.payload).toMatchObject({
        name: '문서 데이터셋',
        datasetType: 'DOCUMENT',
        columns: [],
      });
      // tableName은 doc_<timestamp> 형식 — 백엔드 식별자 규칙([a-z][a-z0-9_]*)을 만족
      expect((req.payload as { tableName: string }).tableName).toMatch(/^doc_\d+$/);
    },
  );
});
