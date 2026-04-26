/**
 * DatasetCreatePage 심화 E2E 테스트
 *
 * DatasetCreatePage.tsx (50%), useDatasets.ts (56.5%), formatters.ts (58%) 커버리지 향상.
 *
 * - 카테고리 선택 UI 및 categoryId payload 전달
 * - 이름/테이블명 입력 + 생성 버튼 → POST /datasets payload 검증
 * - 409 에러 응답 → 에러 메시지 표시
 * - 생성 성공 → 상세 페이지로 이동
 * - 데이터셋 목록 날짜/숫자 포맷 (formatDate, formatters.ts)
 * - 필수 필드 미입력 시 유효성 검사 에러
 */

import { createCategories, createDataset, createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 생성 페이지 URL */
const CREATE_URL = '/data/datasets/new';

/** 카테고리 목록 목업 */
const MOCK_CATEGORIES = createCategories();

/** 생성 성공 응답 */
const MOCK_CREATED_DATASET = createDatasetDetail({ id: 99, name: '새 소방 데이터', tableName: 'new_fire_data' });

/**
 * 데이터셋 생성 페이지로 이동하고 카테고리 목록을 모킹한다.
 */
async function gotoCreatePage(page: import('@playwright/test').Page) {
  await mockApi(page, 'GET', '/api/v1/dataset-categories', MOCK_CATEGORIES);
  await page.goto(CREATE_URL, { waitUntil: 'commit' });
  // "데이터셋 생성" 헤딩이 표시될 때까지 대기
  await expect(page.getByRole('heading', { name: '데이터셋 생성' })).toBeVisible({ timeout: 5000 });
}

test.describe('DatasetCreatePage — 기본 정보 입력', () => {
  test('필수 필드(이름, 테이블명)가 렌더링된다', async ({ authenticatedPage: page }) => {
    await gotoCreatePage(page);

    await expect(page.getByLabel('데이터셋 이름')).toBeVisible();
    await expect(page.getByLabel('테이블명')).toBeVisible();
    await expect(page.getByRole('button', { name: '생성' })).toBeVisible();
    await expect(page.getByRole('button', { name: '취소' })).toBeVisible();
  });

  test('이름과 테이블명 미입력 시 유효성 에러 메시지가 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await gotoCreatePage(page);

    // 빈 폼에서 제출 → handleSubmit → Zod 유효성 검사 실패
    await page.getByRole('button', { name: '생성' }).click();

    // 인라인 에러 메시지 확인 (#69: 토스트 중복 노출 제거)
    await expect(page.getByText('데이터셋 이름을 입력하세요')).toBeVisible({ timeout: 3000 });
    await expect(page.getByText('테이블명을 입력하세요')).toBeVisible();
  });

  test('이름과 테이블명 입력 후 POST /datasets payload가 올바르게 전달된다', async ({
    authenticatedPage: page,
  }) => {
    await gotoCreatePage(page);

    // POST 모킹 + payload 캡처
    let capturedPayload: unknown;
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() === 'POST') {
          capturedPayload = route.request().postDataJSON();
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(MOCK_CREATED_DATASET),
          });
        }
        return route.continue();
      },
    );

    // 이름 입력
    await page.getByLabel('데이터셋 이름').fill('새 소방 데이터');
    // 테이블명 입력
    await page.getByLabel('테이블명').fill('new_fire_data');
    // 컬럼 정의 — SchemaBuilder 첫 번째 행에 필수 columnName 입력
    await page.getByPlaceholder('예: user_id').first().fill('id');

    // 생성 버튼 클릭 — POST 응답을 기다려 payload가 캡처된 이후에 검증한다
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/v1/datasets') && r.request().method() === 'POST'),
      page.getByRole('button', { name: '생성' }).click(),
    ]);
    expect(capturedPayload).toMatchObject({
      name: '새 소방 데이터',
      tableName: 'new_fire_data',
    });
  });

  test('생성 성공 시 데이터셋 상세 페이지로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await gotoCreatePage(page);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() === 'POST') {
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(MOCK_CREATED_DATASET),
          });
        }
        return route.continue();
      },
    );

    // 생성된 데이터셋 상세 API 모킹
    await mockApi(page, 'GET', `/api/v1/datasets/${MOCK_CREATED_DATASET.id}`, MOCK_CREATED_DATASET);

    await page.getByLabel('데이터셋 이름').fill('새 소방 데이터');
    await page.getByLabel('테이블명').fill('new_fire_data');
    await page.getByPlaceholder('예: user_id').first().fill('id');

    await page.getByRole('button', { name: '생성' }).click();

    // navigate(`/data/datasets/${result.data.id}`) — 99번 데이터셋 상세로 이동
    await page.waitForURL(`**/data/datasets/${MOCK_CREATED_DATASET.id}`, { timeout: 5000 });
    expect(page.url()).toContain(`/data/datasets/${MOCK_CREATED_DATASET.id}`);
  });

  test('취소 버튼 클릭 시 데이터셋 목록 페이지로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await gotoCreatePage(page);

    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });

    await page.getByRole('button', { name: '취소' }).click();

    await page.waitForURL('**/data/datasets', { timeout: 5000 });
    expect(page.url()).toContain('/data/datasets');
  });
});

test.describe('DatasetCreatePage — 카테고리 선택 (useCategories)', () => {
  test('카테고리 드롭다운에 목록이 렌더링된다', async ({ authenticatedPage: page }) => {
    await gotoCreatePage(page);

    // 카테고리 Select 트리거 클릭 — 페이지 내 첫 번째 combobox가 카테고리 셀렉터다
    await page.getByRole('combobox').first().click();

    // 카테고리 목록 아이템 확인
    await expect(page.getByRole('option', { name: '소방 데이터' })).toBeVisible({ timeout: 3000 });
    await expect(page.getByRole('option', { name: '통계 데이터' })).toBeVisible({ timeout: 3000 });
    await expect(page.getByRole('option', { name: '기타' })).toBeVisible({ timeout: 3000 });
  });

  test('카테고리 선택 시 POST payload에 categoryId가 포함된다', async ({
    authenticatedPage: page,
  }) => {
    await gotoCreatePage(page);

    let capturedPayload: Record<string, unknown> = {};
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() === 'POST') {
          capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(MOCK_CREATED_DATASET),
          });
        }
        return route.continue();
      },
    );

    await page.getByLabel('데이터셋 이름').fill('소방 카테고리 데이터셋');
    await page.getByLabel('테이블명').fill('fire_cat_dataset');
    await page.getByPlaceholder('예: user_id').first().fill('id');

    // 카테고리 선택: "소방 데이터" (id: 1) — 첫 번째 combobox가 카테고리 셀렉터
    await page.getByRole('combobox').first().click();
    await page.getByRole('option', { name: '소방 데이터' }).click();

    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/v1/datasets') && r.request().method() === 'POST'),
      page.getByRole('button', { name: '생성' }).click(),
    ]);
    // categoryId: 1 이 payload에 포함되어야 함
    expect(capturedPayload.categoryId).toBe(1);
  });

  test('"선택 안 함" 선택 시 payload에 categoryId가 undefined(누락)된다', async ({
    authenticatedPage: page,
  }) => {
    await gotoCreatePage(page);

    let capturedPayload: Record<string, unknown> = {};
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() === 'POST') {
          capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(MOCK_CREATED_DATASET),
          });
        }
        return route.continue();
      },
    );

    await page.getByLabel('데이터셋 이름').fill('카테고리 없는 데이터셋');
    await page.getByLabel('테이블명').fill('no_cat_dataset');
    await page.getByPlaceholder('예: user_id').first().fill('id');

    // 명시적으로 "선택 안 함" 선택 — 첫 번째 combobox가 카테고리 셀렉터
    await page.getByRole('combobox').first().click();
    await page.getByRole('option', { name: '선택 안 함' }).click();

    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/v1/datasets') && r.request().method() === 'POST'),
      page.getByRole('button', { name: '생성' }).click(),
    ]);
    // categoryId가 undefined → JSON 직렬화 시 누락됨
    expect(capturedPayload.categoryId).toBeUndefined();
  });
});

test.describe('DatasetCreatePage — 에러 처리 (useCreateDataset 에러)', () => {
  test('409 충돌 에러 시 에러 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    await gotoCreatePage(page);

    // 409 에러 응답 (테이블명 중복)
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() === 'POST') {
          return route.fulfill({
            status: 409,
            contentType: 'application/json',
            body: JSON.stringify({ message: '이미 존재하는 테이블명입니다.', code: 'DUPLICATE_TABLE' }),
          });
        }
        return route.continue();
      },
    );

    await page.getByLabel('데이터셋 이름').fill('중복 데이터셋');
    await page.getByLabel('테이블명').fill('existing_table');
    await page.getByPlaceholder('예: user_id').first().fill('id');

    await page.getByRole('button', { name: '생성' }).click();

    // handleApiError → extractApiError → errData.message (백엔드 메시지 우선)
    await expect(
      page.getByText('이미 존재하는 테이블명입니다.'),
    ).toBeVisible({ timeout: 3000 });
  });

  test('500 서버 에러 시 에러 토스트가 표시된다', async ({ authenticatedPage: page }) => {
    await gotoCreatePage(page);

    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() === 'POST') {
          return route.fulfill({
            status: 500,
            contentType: 'application/json',
            body: JSON.stringify({ message: '서버 내부 오류가 발생했습니다.' }),
          });
        }
        return route.continue();
      },
    );

    await page.getByLabel('데이터셋 이름').fill('에러 데이터셋');
    await page.getByLabel('테이블명').fill('error_dataset');
    await page.getByPlaceholder('예: user_id').first().fill('id');

    await page.getByRole('button', { name: '생성' }).click();

    // handleApiError → extractApiError → errData.message (백엔드 메시지 우선)
    await expect(
      page.getByText('서버 내부 오류가 발생했습니다.'),
    ).toBeVisible({ timeout: 3000 });
  });
});

test.describe('DatasetCreatePage — 데이터셋 유형 선택', () => {
  test('데이터셋 유형 "파생"으로 변경 시 payload datasetType이 DERIVED이다', async ({
    authenticatedPage: page,
  }) => {
    await gotoCreatePage(page);

    let capturedPayload: Record<string, unknown> = {};
    await page.route(
      (url) => url.pathname === '/api/v1/datasets',
      (route) => {
        if (route.request().method() === 'POST') {
          capturedPayload = route.request().postDataJSON() as Record<string, unknown>;
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(MOCK_CREATED_DATASET),
          });
        }
        return route.continue();
      },
    );

    await page.getByLabel('데이터셋 이름').fill('파생 데이터셋');
    await page.getByLabel('테이블명').fill('derived_dataset');
    await page.getByPlaceholder('예: user_id').first().fill('id');

    // 두 번째 Select가 데이터셋 유형 (기본값 "원본"/SOURCE)
    // combobox들 중 두 번째가 datasetType Select
    const selects = page.getByRole('combobox');
    await selects.nth(1).click();
    await page.getByRole('option', { name: '파생' }).click();

    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/v1/datasets') && r.request().method() === 'POST'),
      page.getByRole('button', { name: '생성' }).click(),
    ]);
    expect(capturedPayload.datasetType).toBe('DERIVED');
  });
});

test.describe('데이터셋 목록 — formatters.ts 날짜/포맷 렌더링', () => {
  test('데이터셋 목록에서 createdAt 날짜가 한국어 형식으로 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // 날짜가 포함된 데이터셋 목록 모킹
    const datasetsWithDate = [
      createDataset({
        id: 1,
        name: '소방 출동 데이터',
        tableName: 'fire_dispatch',
        createdAt: '2026-04-12T09:30:00Z',
      }),
    ];

    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: datasetsWithDate,
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    await mockApi(page, 'GET', '/api/v1/dataset-categories', MOCK_CATEGORIES);

    await page.goto('/data/datasets', { waitUntil: 'commit' });

    // 데이터셋 이름이 목록에 보여야 함
    await expect(page.getByText('소방 출동 데이터')).toBeVisible({ timeout: 5000 });

    // 날짜가 렌더링되는지 확인 (formatDate/formatDateShort — ko-KR 형식)
    // "2026. 4. 12." 또는 "2026. 4. 12. 오전 9:30:00" 형태
    // 정확한 시간대 변환은 환경 의존적이므로 "2026" 포함 여부로 확인
    const dateCell = page.getByText(/2026/);
    await expect(dateCell.first()).toBeVisible({ timeout: 3000 });
  });

  test('데이터셋 목록에 여러 항목이 올바르게 렌더링된다', async ({
    authenticatedPage: page,
  }) => {
    const datasets = [
      createDataset({ id: 1, name: '데이터셋 A', tableName: 'dataset_a', datasetType: 'SOURCE' }),
      createDataset({ id: 2, name: '데이터셋 B', tableName: 'dataset_b', datasetType: 'DERIVED' }),
    ];

    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: datasets,
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
    });
    await mockApi(page, 'GET', '/api/v1/dataset-categories', MOCK_CATEGORIES);

    await page.goto('/data/datasets', { waitUntil: 'commit' });

    await expect(page.getByText('데이터셋 A')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('데이터셋 B')).toBeVisible({ timeout: 3000 });
  });
});
