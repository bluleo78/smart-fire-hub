import { createAdminUserDetail } from '../../factories/auth.factory';
import { createCategories, createColumn, createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 상세 페이지에서 공통으로 필요한 API 모킹 설정
 * - 상세 페이지는 카테고리 목록, 태그 목록을 추가로 호출한다.
 */
async function setupDetailPageMocks(page: import('@playwright/test').Page, datasetId = 1) {
  await setupDatasetDetailMocks(page, datasetId);
  // useCategories() 훅 — 카테고리 셀렉트 박스에 사용 (동적 import → 정적 import로 교체)
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

    // rowCount '100' 표시 확인 (createDatasetDetail의 기본값 rowCount: 100)
    await expect(page.getByText('100').first()).toBeVisible();

    // 데이터셋 유형 배지 — datasetType: 'SOURCE' → '원본' 표시
    await expect(page.getByText('원본').first()).toBeVisible();
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

  /**
   * 회귀 테스트 (issue #14): ?tab=data URL 파라미터가 탭 초기화에 반영되지 않는 버그
   * - URL에 ?tab=data가 있으면 '데이터' 탭이 활성화되어야 한다
   * - 직접 URL 접근·새로고침 시에도 올바른 탭이 유지되어야 한다
   */
  test('?tab=data URL 파라미터로 직접 접근 시 데이터 탭이 활성화된다', async ({
    authenticatedPage: page,
  }) => {
    await setupDetailPageMocks(page, 1);
    // ?tab=data 파라미터를 포함한 URL로 직접 접근
    await page.goto('/data/datasets/1?tab=data');

    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // URL 파라미터에 따라 '데이터' 탭이 선택되어야 한다 (issue #14 회귀 방지)
    await expect(page.getByRole('tab', { name: '데이터' })).toHaveAttribute('data-state', 'active');
    // '정보' 탭은 비활성 상태여야 한다
    await expect(page.getByRole('tab', { name: '정보' })).toHaveAttribute('data-state', 'inactive');
  });

  /**
   * 회귀 테스트 (issue #14): ?tab=columns URL 파라미터로 접근 시 필드 탭이 활성화된다
   */
  test('?tab=columns URL 파라미터로 직접 접근 시 필드 탭이 활성화된다', async ({
    authenticatedPage: page,
  }) => {
    await setupDetailPageMocks(page, 1);
    await page.goto('/data/datasets/1?tab=columns');

    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // '필드' 탭이 활성화되어야 한다
    await expect(page.getByRole('tab', { name: '필드' })).toHaveAttribute('data-state', 'active');
  });

  /**
   * 탭 클릭 시 URL ?tab= 파라미터가 업데이트된다 (링크 공유·뒤로가기 지원)
   */
  test('탭 클릭 시 URL ?tab= 파라미터가 동기화된다', async ({ authenticatedPage: page }) => {
    await setupDetailPageMocks(page, 1);
    await page.goto('/data/datasets/1');

    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // '데이터' 탭 클릭 후 URL 파라미터가 ?tab=data로 업데이트되어야 한다
    await page.getByRole('tab', { name: '데이터' }).click();
    await expect(page).toHaveURL(/[?&]tab=data/);

    // '정보' 탭 클릭 후 기본 탭이므로 URL에서 tab 파라미터가 제거되어야 한다
    await page.getByRole('tab', { name: '정보' }).click();
    await expect(page).not.toHaveURL(/[?&]tab=/);
  });

  test('뒤로 가기 버튼 클릭 시 목록 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDetailPageMocks(page, 1);
    // 목록 페이지로 돌아갈 때 필요한 API 모킹 (동적 import → 정적 import로 교체)
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
    // 정적 import 사용 (파일 최상단의 createCategories 활용)
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets/9999');

    // 404 시 dataset이 null이므로 heading이 표시되지 않아야 함
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).not.toBeVisible();
  });

  test('즐겨찾기 버튼이 렌더링된다', async ({ authenticatedPage: page }) => {
    await setupDetailPageMocks(page, 1);

    // POST /api/v1/datasets/1/favorite 호출 캡처 — 버튼 클릭 시 API가 실제로 호출되는지 검증
    const favoriteCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/favorite',
      { id: 1, isFavorite: true },
      { capture: true },
    );

    await page.goto('/data/datasets/1');

    // 페이지 로드 대기
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 즐겨찾기 버튼 확인 (title 속성으로 식별)
    const favoriteBtn = page.locator('button[title="즐겨찾기 추가"], button[title="즐겨찾기 해제"]');
    await expect(favoriteBtn).toBeVisible();

    // 즐겨찾기 버튼 클릭 후 API 호출 검증
    await favoriteBtn.click();
    const req = await favoriteCapture.waitForRequest();
    // POST 요청이 올바른 엔드포인트로 전송되었는지 확인
    expect(req.url.pathname).toBe('/api/v1/datasets/1/favorite');
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
    // 정적 import 사용 (파일 최상단의 createCategories 활용)
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

  test('GEOMETRY 컬럼이 있는 데이터셋은 "지도" 탭을 표시한다', async ({
    authenticatedPage: page,
  }) => {
    // GEOMETRY 타입 컬럼을 포함하는 데이터셋 모킹
    const detail = createDatasetDetail({
      id: 1,
      columns: [
        createColumn(),
        createColumn({
          id: 99,
          columnName: 'geom',
          displayName: 'Geometry',
          dataType: 'GEOMETRY',
          isPrimaryKey: false,
          columnOrder: 1,
        }),
      ],
    });
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
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // hasGeometry=true → "지도" 탭이 표시되어야 한다
    await expect(page.getByRole('tab', { name: '지도' })).toBeVisible();
  });

  test('GEOMETRY 컬럼 없는 데이터셋은 "지도" 탭을 표시하지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupDetailPageMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // hasGeometry=false → "지도" 탭 없음
    await expect(page.getByRole('tab', { name: '지도' })).not.toBeVisible();
  });

  test('CERTIFIED 상태 데이터셋은 인증 배지를 표시한다', async ({
    authenticatedPage: page,
  }) => {
    const detail = createDatasetDetail({
      id: 1,
      status: 'CERTIFIED',
      statusNote: '공식 인증 데이터셋',
    });
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
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // CERTIFIED 뱃지 확인 (소스: "✓ Certified")
    await expect(page.getByText(/Certified/)).toBeVisible();
    // statusNote 표시 확인
    await expect(page.getByText('공식 인증 데이터셋')).toBeVisible();
  });

  test('DEPRECATED 상태 데이터셋은 사용 중단 배지를 표시한다', async ({
    authenticatedPage: page,
  }) => {
    const detail = createDatasetDetail({
      id: 1,
      status: 'DEPRECATED',
      statusNote: '더 이상 사용되지 않는 데이터셋',
    });
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
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // DEPRECATED 뱃지 확인 (소스: "Deprecated")
    await expect(page.getByText('Deprecated')).toBeVisible();
  });

  test('태그 추가 — POST payload 검증', async ({ authenticatedPage: page }) => {
    await setupDetailPageMocks(page, 1);

    const addTagCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/tags',
      { id: 1, tags: ['테스트', '샘플', '신규태그'] },
      { capture: true },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 태그 추가 버튼(+) 클릭 → Popover 열기
    await page.locator('button[title="태그 추가"]').click();

    // 태그 입력 (Popover 내 input)
    const tagInput = page.getByPlaceholder(/태그 입력|새 태그/);
    await tagInput.fill('신규태그');

    // Enter로 태그 추가 제출
    await tagInput.press('Enter');

    // POST payload 검증
    const req = await addTagCapture.waitForRequest();
    expect(req.payload).toMatchObject({ tagName: '신규태그' });
  });

  test('기존 태그 X 버튼 클릭 — DELETE 호출 검증', async ({ authenticatedPage: page }) => {
    await setupDetailPageMocks(page, 1);

    let removeTagCalled = false;
    await page.route(
      (url) => url.pathname.startsWith('/api/v1/datasets/1/tags/'),
      (route) => {
        if (route.request().method() === 'DELETE') {
          removeTagCalled = true;
          return route.fulfill({ status: 204, body: '' });
        }
        return route.fallback();
      },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // "테스트" 태그의 X 버튼 클릭 (태그 badge 내 X 아이콘)
    const testTagBadge = page.locator('[data-slot="badge"]').filter({ hasText: '테스트' });
    await testTagBadge.getByRole('button').click();

    await expect.poll(() => removeTagCalled).toBe(true);
  });

  test('관리자는 상태 변경 버튼을 볼 수 있고 CERTIFIED로 변경할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    // 관리자 계정으로 users/me 오버라이드
    await mockApi(page, 'GET', '/api/v1/users/me', createAdminUserDetail());
    await setupDetailPageMocks(page, 1);

    // PUT /api/v1/datasets/1/status 캡처
    const statusCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/datasets/1/status',
      { id: 1, status: 'CERTIFIED', statusNote: '공식 데이터셋' },
      { capture: true },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 관리자에게만 보이는 "상태 변경" 버튼 확인
    await expect(page.getByRole('button', { name: '상태 변경' })).toBeVisible();

    // Popover 열기
    await page.getByRole('button', { name: '상태 변경' }).click();

    // CERTIFIED 선택
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: /인증됨|CERTIFIED/ }).click();

    // 상태 노트 입력
    await page.getByPlaceholder('상태 노트 (선택)').fill('공식 데이터셋');

    // 저장 버튼 클릭
    await page.getByRole('button', { name: '저장', exact: true }).click();

    // PUT payload 검증
    const req = await statusCapture.waitForRequest();
    expect(req.payload).toMatchObject({ status: 'CERTIFIED' });
  });

  test('복제 버튼 클릭 시 복제 다이얼로그가 열리고 POST payload가 전송된다', async ({
    authenticatedPage: page,
  }) => {
    await setupDetailPageMocks(page, 1);

    // POST /api/v1/datasets/1/clone 모킹
    const cloneCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/clone',
      createDatasetDetail({ id: 2, name: '테스트 데이터셋_copy', tableName: 'test_dataset_copy' }),
      { capture: true },
    );
    // 복제 후 이동할 상세 페이지 모킹
    await mockApi(page, 'GET', '/api/v1/datasets/2', createDatasetDetail({ id: 2, name: '테스트 데이터셋_copy', tableName: 'test_dataset_copy' }));

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 복제 버튼 클릭
    await page.getByRole('button', { name: '복제' }).click();

    // 복제 다이얼로그가 열려야 한다
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('heading', { name: '데이터셋 복제' })).toBeVisible();

    // 기본값 확인 — 이름에 "_copy" 접미사
    const nameInput = page.getByLabel('데이터셋 이름 *');
    await expect(nameInput).toHaveValue('테스트 데이터셋_copy');

    // 복제 버튼 클릭 → POST 호출
    await page.getByRole('dialog').getByRole('button', { name: '복제' }).click();

    // payload 검증
    const req = await cloneCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '테스트 데이터셋_copy',
      tableName: 'test_dataset_copy',
    });
  });

  test('복제 다이얼로그 — 테이블명 유효성 검사: 대문자 입력 시 에러 표시', async ({
    authenticatedPage: page,
  }) => {
    await setupDetailPageMocks(page, 1);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 복제 버튼 클릭
    await page.getByRole('button', { name: '복제' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 테이블명에 대문자 입력 (유효성 검사 실패 케이스)
    const tableNameInput = page.getByLabel('테이블 이름 *');
    await tableNameInput.clear();
    await tableNameInput.fill('InvalidTable');

    // 복제 버튼 클릭
    await page.getByRole('dialog').getByRole('button', { name: '복제' }).click();

    // 유효성 에러 메시지 표시 확인
    await expect(page.getByText(/영소문자로 시작/)).toBeVisible();
  });

  test('TEMP 타입 데이터셋은 임시 배지를 표시한다', async ({ authenticatedPage: page }) => {
    const detail = createDatasetDetail({ id: 1, datasetType: 'TEMP' });
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
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // TEMP 뱃지 확인 — 헤더의 text-xs 뱃지 (strict 모드: .first() 사용)
    await expect(page.getByText('임시').first()).toBeVisible();
  });

  /**
   * 태그 자동완성 제안 목록에서 항목 클릭 시 addTag.mutateAsync(suggestion) 경로 커버.
   * DatasetDetailPage.tsx line 276-289: filteredTagSuggestions 클릭 핸들러
   */
  test('태그 자동완성 제안 클릭 시 POST API가 호출된다', async ({
    authenticatedPage: page,
  }) => {
    // 현재 데이터셋에 없는 태그를 제안 목록에 포함 (filteredTagSuggestions 필터 통과)
    const detail = createDatasetDetail({ id: 1, tags: ['테스트'] });
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
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    // 제안 목록에 '샘플' 포함 — 현재 tags에 없으므로 filteredTagSuggestions에 표시된다
    await mockApi(page, 'GET', '/api/v1/datasets/tags', ['샘플', 'production']);

    const addTagCapture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/tags',
      { id: 1, tags: ['테스트', '샘플'] },
      { capture: true },
    );

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 태그 추가 버튼(+) 클릭 → Popover 열기
    await page.locator('button[title="태그 추가"]').click();

    // 입력 필드에 '샘' 입력 → '샘플' 제안 항목이 필터링되어 표시된다
    const tagInput = page.getByPlaceholder(/태그 입력/);
    await tagInput.fill('샘');

    // 자동완성 제안 목록에서 '샘플' 버튼 클릭 → suggestion 클릭 핸들러 실행
    const suggestionBtn = page.getByRole('button', { name: '샘플' });
    await expect(suggestionBtn).toBeVisible({ timeout: 3000 });
    await suggestionBtn.click();

    // POST payload 검증 — suggestion 클릭 시 addTag.mutateAsync(suggestion) 호출
    const req = await addTagCapture.waitForRequest();
    expect(req.payload).toMatchObject({ tagName: '샘플' });
  });

  /**
   * 이미 추가된 태그를 다시 입력하면 에러 토스트가 표시된다.
   * DatasetDetailPage.tsx line 95-98: handleAddTag → tags.includes(tag) 분기
   */
  test('이미 추가된 태그 입력 시 에러 메시지가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await setupDetailPageMocks(page, 1);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 태그 추가 버튼(+) 클릭 → Popover 열기
    await page.locator('button[title="태그 추가"]').click();

    // 이미 존재하는 태그 '테스트' 입력 (createDatasetDetail tags: ['테스트', '샘플'])
    const tagInput = page.getByPlaceholder(/태그 입력/);
    await tagInput.fill('테스트');

    // 추가 버튼 클릭 → dataset.tags.includes(tag) → toast.error() 호출
    // 페이지에 '추가' 버튼이 여러 개 있으므로 tagInput 근처 버튼을 선택한다
    await tagInput.press('Enter');

    // 에러 토스트 표시 확인
    await expect(page.getByText(/이미 추가된 태그/)).toBeVisible({ timeout: 5000 });
  });

  /**
   * 관리자 상태 변경 Popover에서 취소 버튼 클릭 시 Popover가 닫힌다.
   * DatasetDetailPage.tsx line 217-220: 취소 버튼 → setStatusEditOpen(false) 분기
   */
  test('관리자 상태 변경 팝오버에서 취소 버튼 클릭 시 팝오버가 닫힌다', async ({
    authenticatedPage: page,
  }) => {
    const { createAdminUserDetail } = await import('../../factories/auth.factory');
    // 관리자 계정으로 users/me 오버라이드
    await mockApi(page, 'GET', '/api/v1/users/me', createAdminUserDetail());
    await setupDetailPageMocks(page, 1);

    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();

    // 관리자에게만 보이는 "상태 변경" 버튼 확인
    await expect(page.getByRole('button', { name: '상태 변경' })).toBeVisible();

    // Popover 열기
    await page.getByRole('button', { name: '상태 변경' }).click();

    // Popover 콘텐츠가 열려야 한다
    await expect(page.getByText('데이터셋 상태 변경')).toBeVisible({ timeout: 3000 });

    // 취소 버튼 클릭 → setStatusEditOpen(false) 호출
    await page.getByRole('button', { name: '취소' }).click();

    // Popover가 닫혀야 한다 (콘텐츠 비표시)
    await expect(page.getByText('데이터셋 상태 변경')).not.toBeVisible({ timeout: 3000 });
  });
});
