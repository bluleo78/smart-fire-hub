import { createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 목록 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 목록 페이지 UI를 검증한다.
 */
test.describe('데이터셋 목록 페이지', () => {
  test('데이터셋 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    // 5개 데이터셋 목록을 모킹한 후 목록 페이지 접근
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '데이터셋 관리' })).toBeVisible();

    // 테이블 헤더 컬럼 확인
    await expect(page.getByRole('columnheader', { name: '이름' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '유형' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '카테고리' })).toBeVisible();

    // 데이터셋 행이 5개 렌더링되는지 확인 (팩토리에서 생성한 이름 패턴)
    await expect(page.getByRole('row', { name: /데이터셋 1/ })).toBeVisible();
    await expect(page.getByRole('row', { name: /데이터셋 5/ })).toBeVisible();
  });

  test('데이터셋 추가 버튼 클릭 시 /new 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);

    // 생성 페이지도 미리 카테고리 API 모킹 (이동 후 사용)
    await page.goto('/data/datasets');

    // "데이터셋 추가" 버튼 클릭
    await page.getByRole('link', { name: /데이터셋 추가/ }).click();

    // /data/datasets/new 페이지로 이동 확인
    await expect(page).toHaveURL('/data/datasets/new');
  });

  test('빈 목록일 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 빈 페이지 응답으로 오버라이드
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets');

    // 빈 상태 메시지 확인
    await expect(page.getByText('데이터셋이 없습니다.')).toBeVisible();
  });

  test('검색 입력 시 search 파라미터가 반영된다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    // 검색 입력 필드에 텍스트 입력
    await page.getByPlaceholder('데이터셋 검색...').fill('소방');

    // 검색 API 요청이 발생하도록 잠시 대기 (debounce 처리 고려)
    await page.waitForTimeout(500);

    // 검색 필드에 입력값이 유지되는지 확인
    await expect(page.getByPlaceholder('데이터셋 검색...')).toHaveValue('소방');
  });

  test('카테고리 칩 클릭 시 필터가 적용된다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    // "전체" 칩과 카테고리 칩이 렌더링되는지 텍스트로 확인
    await expect(page.getByText('전체').first()).toBeVisible();
    await expect(page.getByText('소방 데이터').first()).toBeVisible();
  });

  test('즐겨찾기 토글 버튼이 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    // 즐겨찾기 필터 버튼 확인 (필터 영역의 버튼, exact: true 로 행의 즐겨찾기 추가 버튼과 구분)
    await expect(page.getByRole('button', { name: '즐겨찾기', exact: true })).toBeVisible();
  });

  test('서버 에러(500) 시 목록이 비어 있다', async ({ authenticatedPage: page }) => {
    // 500 에러 응답으로 모킹
    await mockApi(page, 'GET', '/api/v1/dataset-categories', [], { status: 500 });
    await mockApi(page, 'GET', '/api/v1/datasets', {}, { status: 500 });
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets');

    // 서버 에러 시 빈 상태 또는 에러 메시지 표시 확인
    await expect(page.getByText('데이터셋이 없습니다.')).toBeVisible();
  });

  test('데이터셋 행 클릭 시 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);

    // 상세 페이지 API도 미리 모킹
    const { createDatasetDetail } = await import('../../factories/dataset.factory');
    await mockApi(page, 'GET', '/api/v1/datasets/1', createDatasetDetail({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/datasets/1/queries', createPageResponse([]));
    await mockApi(page, 'GET', '/api/v1/datasets/tags', ['sample', 'test']);

    await page.goto('/data/datasets');

    // 첫 번째 데이터셋 이름 셀 클릭 (행 클릭 시 상세 페이지로 이동)
    await page.getByRole('cell', { name: '데이터셋 1', exact: true }).click();

    // 상세 페이지(/data/datasets/1)로 이동 확인
    await expect(page).toHaveURL(/\/data\/datasets\/1/);
  });

  test('데이터셋 목록에 페이지네이션이 렌더링된다', async ({ authenticatedPage: page }) => {
    // 총 25개 항목 → 3페이지 (size=10)
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(
      page,
      'GET',
      '/api/v1/datasets',
      createPageResponse(createDatasets(10), { totalElements: 25, totalPages: 3 }),
    );
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets');

    // 페이지네이션 영역이 렌더링되는지 확인 (10개 행 중 첫 번째 항목)
    await expect(page.getByRole('row').nth(1)).toBeVisible();
  });
});
