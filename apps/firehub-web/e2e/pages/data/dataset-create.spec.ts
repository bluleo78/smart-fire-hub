import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 생성 페이지 E2E 테스트
 * - 폼 렌더링, 유효성 검사, 취소 플로우, 서버 에러를 검증한다.
 */
test.describe('데이터셋 생성 페이지', () => {
  test('생성 폼이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    // 카테고리 API 모킹 (폼에서 카테고리 셀렉트 박스에 사용)
    await setupDatasetMocks(page);
    await page.goto('/data/datasets/new');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '데이터셋 생성' })).toBeVisible();

    // 필수 입력 필드 확인
    await expect(page.getByLabel('데이터셋 이름')).toBeVisible();
    await expect(page.getByLabel('테이블명')).toBeVisible();

    // 제출 버튼 및 취소 버튼 확인
    await expect(page.getByRole('button', { name: '생성' })).toBeVisible();
    await expect(page.getByRole('button', { name: '취소' })).toBeVisible();

    // 섹션 헤더 확인
    await expect(page.getByRole('heading', { name: '기본 정보' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '칼럼 정의' })).toBeVisible();
  });

  test('필수 필드 없이 제출 시 유효성 에러가 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets/new');

    // 빈 폼 상태에서 생성 버튼 클릭
    await page.getByRole('button', { name: '생성' }).click();

    // 유효성 에러 토스트 또는 인라인 에러 메시지 확인
    // createDatasetSchema 기반 에러 메시지가 표시되어야 한다
    await expect(
      page.getByText(/입력값을 확인해주세요|필수 항목|누락/),
    ).toBeVisible();
  });

  test('취소 버튼 클릭 시 목록 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets/new');

    // 취소 버튼 클릭
    await page.getByRole('button', { name: '취소' }).click();

    // 데이터셋 목록 페이지로 이동 확인
    await expect(page).toHaveURL('/data/datasets');
  });

  test('서버 에러(409 중복) 시 에러 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);

    // 데이터셋 생성 API를 409 충돌 에러로 모킹
    await mockApi(
      page,
      'POST',
      '/api/v1/datasets',
      { status: 409, message: '이미 존재하는 테이블명입니다.' },
      { status: 409 },
    );

    await page.goto('/data/datasets/new');

    // 데이터셋 이름과 테이블명 입력
    await page.getByLabel('데이터셋 이름').fill('테스트 데이터셋');
    await page.getByLabel('테이블명').fill('test_dataset');

    // SchemaBuilder의 첫 번째 칼럼명 입력 (필수)
    await page.getByPlaceholder('예: user_id').fill('col_name');

    // 생성 버튼 클릭
    await page.getByRole('button', { name: '생성' }).click();

    // 409 에러 메시지 표시 확인 (Sonner 토스트)
    await expect(page.getByText(/이미 존재하는 테이블명|생성에 실패/)).toBeVisible();
  });

  test('폼 입력 후 정상 생성 시 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);

    // 데이터셋 생성 성공 응답 모킹
    await mockApi(page, 'POST', '/api/v1/datasets', { id: 99, name: '신규 데이터셋' });
    // 상세 페이지 이동 후 필요한 API 모킹 (setupDatasetDetailMocks 역할)
    const { createDatasetDetail } = await import('../../factories/dataset.factory');
    const { createPageResponse } = await import('../../fixtures/api-mock');
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
    await mockApi(page, 'GET', '/api/v1/datasets/tags', ['sample']);

    await page.goto('/data/datasets/new');

    // 필수 필드 입력
    await page.getByLabel('데이터셋 이름').fill('신규 데이터셋');
    await page.getByLabel('테이블명').fill('new_dataset');

    // SchemaBuilder의 첫 번째 칼럼명 입력 (필수, 영문 소문자로 시작)
    await page.getByPlaceholder('예: user_id').fill('col_name');

    // 생성 버튼 클릭
    await page.getByRole('button', { name: '생성' }).click();

    // 성공 후 상세 페이지(/data/datasets/99)로 이동 확인
    await expect(page).toHaveURL('/data/datasets/99');
  });
});
