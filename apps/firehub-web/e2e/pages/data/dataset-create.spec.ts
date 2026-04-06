import { createDatasetDetail } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 생성 페이지 E2E 테스트
 * - 폼 렌더링, 유효성 검사, 취소 플로우, 서버 에러, API payload 검증을 수행한다.
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

    // 빈 폼 상태에서 생성 버튼 클릭 — onInvalid 콜백이 실행된다
    await page.getByRole('button', { name: '생성' }).click();

    // handleSubmit의 onInvalid 콜백에서 표시되는 정확한 토스트 메시지 확인
    await expect(
      page.getByText('입력값을 확인해주세요. 필수 항목이 누락되었거나 형식이 올바르지 않습니다.'),
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

    // handleApiError가 표시하는 정확한 서버 에러 메시지 확인 (Sonner 토스트)
    await expect(page.getByText('이미 존재하는 테이블명입니다.')).toBeVisible();
  });

  test('폼 입력 후 정상 생성 시 상세 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);

    // 데이터셋 생성 POST API를 capture: true로 모킹하여 payload를 캡처한다
    const capture = await mockApi(
      page,
      'POST',
      '/api/v1/datasets',
      { id: 99, name: '신규 데이터셋' },
      { capture: true },
    );

    // 상세 페이지 이동 후 필요한 API 모킹
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

    // API에 전달된 payload 검증 — 입력값이 정확히 전달되는지 확인한다
    const req = await capture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '신규 데이터셋',
      tableName: 'new_dataset',
      datasetType: 'SOURCE',
      columns: [expect.objectContaining({ columnName: 'col_name' })],
    });

    // 성공 후 상세 페이지(/data/datasets/99)로 이동 확인
    await expect(page).toHaveURL('/data/datasets/99');
  });

  test('테이블명에 대문자 입력 시 유효성 에러가 표시된다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    await page.goto('/data/datasets/new');

    // 이름과 대문자가 포함된 유효하지 않은 테이블명 입력
    await page.getByLabel('데이터셋 이름').fill('테스트');
    await page.getByLabel('테이블명').fill('InvalidName');

    // SchemaBuilder의 첫 번째 칼럼명 입력 (칼럼 유효성은 통과)
    await page.getByPlaceholder('예: user_id').fill('col1');

    // 생성 버튼 클릭 — Zod 스키마가 onSubmit 모드이므로 제출 시 검증된다
    await page.getByRole('button', { name: '생성' }).click();

    // createDatasetSchema의 tableName regex 규칙에서 발생하는 인라인 에러 메시지 확인
    // 이 에러는 Zod resolver가 개별 필드 에러로 처리하여 <p> 태그로 폼 아래 표시된다
    await expect(
      page.getByText('영문 소문자, 숫자, 밑줄만 사용 가능합니다'),
    ).toBeVisible();
  });
});
