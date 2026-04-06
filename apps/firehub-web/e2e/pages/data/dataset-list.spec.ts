import { createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 목록 페이지 E2E 테스트
 * - API 모킹 기반으로 백엔드 없이 목록 페이지 UI를 검증한다.
 * - 단순 가시성 확인을 넘어 셀 단위 데이터 검증, API 파라미터 검증,
 *   비즈니스 로직(즐겨찾기 토글, 페이지네이션 등)까지 검증한다.
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

    // 데이터 행이 정확히 5개 렌더링되는지 확인 (헤더 행 제외)
    // getByRole('row')는 헤더 포함이므로 nth(0)이 헤더 — 데이터 행은 1번~5번
    const rows = page.getByRole('row');
    await expect(rows).toHaveCount(6); // 헤더 1개 + 데이터 5개

    // '데이터셋 1' 행에 '원본' 유형 배지와 '기본 카테고리' 카테고리 텍스트가 렌더링되는지 셀 단위 검증
    const firstDataRow = page.getByRole('row', { name: /데이터셋 1/ });
    await expect(firstDataRow).toBeVisible();
    // datasetType='SOURCE' → '원본' 배지
    await expect(firstDataRow.getByText('원본')).toBeVisible();
    // category.name='기본 카테고리' (createDatasets는 createCategory() 기본값을 사용)
    await expect(firstDataRow.getByText('기본 카테고리')).toBeVisible();

    // 마지막 행도 렌더링 확인
    await expect(page.getByRole('row', { name: /데이터셋 5/ })).toBeVisible();
  });

  test('데이터셋 추가 버튼 클릭 시 /new 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);

    // 생성 페이지도 미리 카테고리 API 모킹 (이동 후 사용)
    await page.goto('/data/datasets');

    // "데이터셋 추가" 버튼 클릭 (Link → <a> role="link")
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

    // 데이터 행이 없는지 확인 (헤더 행 + 빈 상태 행 = 2개)
    // 테이블은 빈 상태일 때 "데이터셋이 없습니다." 메시지를 담은 행을 1개 렌더링한다
    const rows = page.getByRole('row');
    await expect(rows).toHaveCount(2); // 헤더 1개 + 빈 상태 행 1개
  });

  test('검색 입력 시 search 파라미터가 반영된다', async ({ authenticatedPage: page }) => {
    // 초기 모킹 설정 후 페이지 이동
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    // 페이지가 완전히 렌더링될 때까지 대기
    await expect(page.getByRole('heading', { name: '데이터셋 관리' })).toBeVisible();

    // 검색 재요청을 캡처하기 위해 capture: true로 재모킹 (나중에 등록된 route가 우선 적용)
    // 이 시점 이후의 GET /api/v1/datasets 요청만 캡처된다
    const capture = await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]), {
      capture: true,
    });

    // 검색 입력 필드에 텍스트 입력
    await page.getByPlaceholder('데이터셋 검색...').fill('소방');

    // debounce 처리 후 검색 재요청이 발생할 때까지 대기 (최대 10초)
    const req = await capture.waitForRequest();

    // API 요청의 search 쿼리 파라미터가 올바르게 전달되는지 검증
    expect(req.searchParams.get('search')).toBe('소방');

    // 검색 필드에 입력값이 유지되는지 확인
    await expect(page.getByPlaceholder('데이터셋 검색...')).toHaveValue('소방');
  });

  test('카테고리 칩 클릭 시 필터가 적용된다', async ({ authenticatedPage: page }) => {
    // 초기 모킹 (카테고리 3개: 소방 데이터 id=1, 통계 데이터 id=2, 기타 id=3)
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    // "전체" 칩과 "소방 데이터" 카테고리 칩이 렌더링되는지 확인
    await expect(page.getByText('전체').first()).toBeVisible();
    await expect(page.getByText('소방 데이터').first()).toBeVisible();

    // 카테고리 칩 클릭 시 categoryId 파라미터 검증을 위해 capture: true로 재모킹
    const capture = await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([]), {
      capture: true,
    });

    // '소방 데이터' 카테고리 칩 클릭 (Badge 컴포넌트)
    await page.getByText('소방 데이터').first().click();

    // API 요청에 categoryId=1이 전달되는지 검증
    const req = await capture.waitForRequest();
    expect(req.searchParams.get('categoryId')).toBe('1');
  });

  test('즐겨찾기 토글 버튼이 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    // 즐겨찾기 필터 버튼 확인 (필터 영역의 버튼, exact: true로 행의 '즐겨찾기 추가' 버튼과 구분)
    await expect(page.getByRole('button', { name: '즐겨찾기', exact: true })).toBeVisible();

    // 첫 번째 행의 즐겨찾기 토글 버튼 확인 (isFavorite=false → aria-label='즐겨찾기 추가')
    const starBtn = page.getByRole('button', { name: '즐겨찾기 추가' }).first();
    await expect(starBtn).toBeVisible();

    // 즐겨찾기 토글 API 캡처 모킹: POST /api/v1/datasets/1/favorite
    const capture = await mockApi(page, 'POST', '/api/v1/datasets/1/favorite', {}, {
      capture: true,
    });

    // 즐겨찾기 토글 버튼 클릭 → API가 호출되는지 검증
    await starBtn.click();
    const req = await capture.waitForRequest();

    // POST 요청이 올바른 엔드포인트로 전달됐는지 확인
    expect(req.url.pathname).toBe('/api/v1/datasets/1/favorite');
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
    // 총 25개 항목 → 3페이지 (size=10)로 모킹, 현재 페이지는 10개 행 렌더링
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(
      page,
      'GET',
      '/api/v1/datasets',
      createPageResponse(createDatasets(10), { totalElements: 25, totalPages: 3 }),
    );
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets');

    // 데이터 행이 10개 렌더링되는지 확인 (헤더 1개 포함 총 11행)
    const rows = page.getByRole('row');
    await expect(rows).toHaveCount(11); // 헤더 1개 + 데이터 10개

    // 페이지네이션 버튼(다음/이전)이 렌더링되는지 확인
    await expect(page.getByRole('button', { name: /다음/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /이전/ })).toBeVisible();
  });
});
