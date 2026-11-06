import { createCategories } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks, setupDatasetMocks } from '../../fixtures/dataset.fixture';

// 만들기·정보 수정 흐름을 검증하므로 데이터셋 쓰기 권한을 준다(기본 인증 fixture 는 권한이 비어 있다).
test.beforeEach(async ({ authenticatedPage: page }) => {
  await grantDatasetPermissions(page);
});

/**
 * 데이터셋 이름 공백 검증 (WD-502)
 * - 공백만 넣은 이름이 클라이언트 검증을 통과해 서버 @NotBlank 400 의 영어 토스트(「must not be blank」·「Validation failed」)로
 *   이어지던 결함의 회귀 방지. 이름 칸 아래 한국어 안내가 뜨고 요청이 나가지 않아야 한다.
 */
test.describe('데이터셋 이름 공백 검증 (WD-502)', () => {
  test('만들기: 공백만 넣은 이름은 이름 칸에 안내하고 생성 요청을 보내지 않는다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    const create = await mockApi(page, 'POST', '/api/v1/datasets', {}, { status: 400, capture: true });

    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('   ');
    await page.getByLabel('테이블명').fill('tbl_qa3_space');
    await page.getByPlaceholder('예: user_id').fill('c1');
    await page.getByRole('button', { name: '생성' }).click();

    // 이름 칸 한국어 안내 + 포커스, 서버 영어 메시지는 없다
    await expect(page.getByText('데이터셋 이름을 입력하세요')).toBeVisible();
    await expect(page.getByLabel('데이터셋 이름')).toBeFocused();
    await expect(page.getByText('must not be blank')).toHaveCount(0);
    expect(create.requests).toHaveLength(0);
  });

  test('만들기: 이름 앞뒤 공백은 잘라서 보낸다', async ({ authenticatedPage: page }) => {
    await setupDatasetMocks(page);
    const create = await mockApi(page, 'POST', '/api/v1/datasets', { id: 99 }, { status: 201, capture: true });
    // 생성 뒤 상세 페이지 이동에 필요한 최소 모킹
    await setupDatasetDetailMocks(page, 99);
    await mockApi(page, 'GET', '/api/v1/datasets/99/queries', createPageResponse([]));

    await page.goto('/data/datasets/new');
    await page.getByLabel('데이터셋 이름').fill('  매출 데이터  ');
    await page.getByLabel('테이블명').fill('sales_data');
    await page.getByPlaceholder('예: user_id').fill('c1');
    await page.getByRole('button', { name: '생성' }).click();

    const req = await create.waitForRequest();
    expect(req.payload).toMatchObject({ name: '매출 데이터', tableName: 'sales_data' });
  });

  test('정보 수정: 공백만 넣은 이름은 이름 칸에 안내하고 저장 요청을 보내지 않는다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    const update = await mockApi(page, 'PUT', '/api/v1/datasets/1', {}, { status: 400, capture: true });

    await page.goto('/data/datasets/1');
    await page.getByRole('button', { name: '수정', exact: true }).click();
    await page.getByLabel('데이터셋 이름 *').fill('   ');
    await page.getByRole('button', { name: '저장', exact: true }).click();

    await expect(page.getByText('데이터셋 이름을 입력하세요')).toBeVisible();
    await expect(page.getByText('Validation failed')).toHaveCount(0);
    expect(update.requests).toHaveLength(0);
  });
});
