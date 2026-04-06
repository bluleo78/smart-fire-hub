import { createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 상세 페이지에서 공통으로 필요한 API 모킹 설정
 * - 상세 페이지는 카테고리 목록, 태그 목록을 추가로 호출한다.
 */
async function setupDetailPageMocks(page: import('@playwright/test').Page, datasetId = 1) {
  await setupDatasetDetailMocks(page, datasetId);
  // useCategories() 훅 — 카테고리 셀렉트 박스에 사용
  const { createCategories } = await import('../../factories/dataset.factory');
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  // useTags() 훅 — 태그 자동완성에 사용
  await mockApi(page, 'GET', '/api/v1/datasets/tags', ['sample', 'test']);
}

/**
 * 데이터셋 상세 페이지 E2E 테스트
 * - 상세 정보 렌더링, 탭 전환, 에러 처리, 즐겨찾기, 태그 기능을 검증한다.
 */
test.describe('데이터셋 상세 페이지', () => {
  test('데이터셋 상세 정보가 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupDetailPageMocks(page, 1);
    await page.goto('/data/datasets/1');

    // 데이터셋 이름 표시 확인 (createDatasetDetail의 기본값)
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 테이블명 표시 확인 (헤더 영역의 font-mono span)
    await expect(page.getByText('test_dataset').first()).toBeVisible();

    // 태그 표시 확인 (createDatasetDetail에서 tags: ['테스트', '샘플'])
    await expect(page.getByText('테스트').first()).toBeVisible();
    await expect(page.getByText('샘플').first()).toBeVisible();
  });

  test('탭이 올바르게 렌더링되고 전환된다', async ({ authenticatedPage: page }) => {
    await setupDetailPageMocks(page, 1);
    await page.goto('/data/datasets/1');

    // 기본 탭(정보)이 활성화되어 있는지 확인
    await expect(page.getByRole('tab', { name: '정보' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '필드' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '데이터' })).toBeVisible();
    await expect(page.getByRole('tab', { name: '이력' })).toBeVisible();

    // "필드" 탭 클릭 후 탭 전환 확인
    await page.getByRole('tab', { name: '필드' }).click();
    await expect(page.getByRole('tab', { name: '필드' })).toHaveAttribute('data-state', 'active');
  });

  test('뒤로 가기 버튼 클릭 시 목록 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDetailPageMocks(page, 1);
    // 목록 페이지로 돌아갈 때 필요한 API 모킹
    const { createDatasets } = await import('../../factories/dataset.factory');
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse(createDatasets(5)));

    await page.goto('/data/datasets/1');

    // 페이지가 완전히 로드될 때까지 대기 (heading 표시 확인)
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // ArrowLeft 뒤로 가기 버튼 클릭 — main 영역 첫 번째 버튼
    await page.locator('main button').first().click();

    // 데이터셋 목록 페이지로 이동 확인
    await expect(page).toHaveURL('/data/datasets');
  });

  test('존재하지 않는 데이터셋(404) 접근 시 로딩 상태가 유지된다', async ({
    authenticatedPage: page,
  }) => {
    // 404 응답으로 모킹 — dataset이 null이면 Skeleton을 렌더링하고 heading은 표시되지 않는다
    await mockApi(page, 'GET', '/api/v1/datasets/9999', { message: 'Not found' }, { status: 404 });
    const { createCategories } = await import('../../factories/dataset.factory');
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets/9999');

    // 404 시 dataset이 null이므로 heading이 표시되지 않아야 함
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).not.toBeVisible();
  });

  test('즐겨찾기 버튼이 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupDetailPageMocks(page, 1);
    await page.goto('/data/datasets/1');

    // 페이지 로드 대기
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 즐겨찾기 버튼 확인 (title 속성으로 식별)
    const favoriteBtn = page.locator('button[title="즐겨찾기 추가"], button[title="즐겨찾기 해제"]');
    await expect(favoriteBtn).toBeVisible();
  });

  test('즐겨찾기 상태인 데이터셋은 채워진 별 아이콘을 표시한다', async ({
    authenticatedPage: page,
  }) => {
    // isFavorite: true로 설정한 데이터셋 모킹
    const detail = createDatasetDetail({ id: 1, isFavorite: true });
    await mockApi(page, 'GET', '/api/v1/datasets/1', detail);
    await mockApi(page, 'GET', '/api/v1/datasets/1/data', {
      columns: detail.columns,
      rows: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });
    await mockApi(page, 'GET', '/api/v1/datasets/1/stats', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    const { createCategories } = await import('../../factories/dataset.factory');
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets/1');

    // 페이지 로드 대기
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 즐겨찾기 해제 버튼이 표시되어야 한다 (이미 즐겨찾기된 상태)
    const favoriteBtn = page.locator('button[title="즐겨찾기 해제"]');
    await expect(favoriteBtn).toBeVisible();
  });

  test('태그 추가 버튼이 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupDetailPageMocks(page, 1);
    await page.goto('/data/datasets/1');

    // 페이지 로드 대기
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 태그 추가 버튼(+ 아이콘 버튼, title="태그 추가") 확인
    await expect(page.locator('button[title="태그 추가"]')).toBeVisible();
  });
});
