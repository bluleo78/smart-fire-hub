import type { Page } from '@playwright/test';

import { createCategories, createDatasetDetail } from '../../factories/dataset.factory';
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
    await page.getByRole('button', { name: '만들기' }).click();

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
    await page.getByRole('button', { name: '만들기' }).click();

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

/**
 * 데이터셋 복제 이름 검증 (WD-523)
 * - 복제 다이얼로그가 이름 규칙을 따로 적어 trim·길이 상한이 빠져 있었다 — 공백만 넣은 이름이 서버 @NotBlank 400 의 영어 토스트로,
 *   긴 이름이 DB 길이 오류로 이어지던 결함의 회귀 방지. 만들기·정보 수정과 같은 공용 이름 스키마를 쓰는지 본다.
 */
test.describe('데이터셋 복제 이름 검증 (WD-523)', () => {
  /** 데이터셋 1 상세를 열고 복제 다이얼로그를 띄워 돌려준다. */
  async function openCloneDialog(page: Page) {
    await page.goto('/data/datasets/1');
    // 상세가 다 그려진 뒤 복제를 누른다(차가운 dev 서버에서 로딩 중 버튼을 누르는 경합 방지)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await page.getByRole('button', { name: '복제' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    return dialog;
  }

  /** 복제 성공 응답(새 데이터셋 2)과 이동할 상세 화면을 모킹하고 요청 캡처를 돌려준다. */
  async function mockCloneSuccess(page: Page) {
    const clone = await mockApi(
      page,
      'POST',
      '/api/v1/datasets/1/clone',
      createDatasetDetail({ id: 2, name: '사본' }),
      { capture: true },
    );
    await setupDatasetDetailMocks(page, 2);
    return clone;
  }

  test('복제: 공백만 넣은 이름은 이름 칸에 안내하고 복제 요청을 보내지 않는다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, 1);
    const clone = await mockApi(page, 'POST', '/api/v1/datasets/1/clone', {}, { status: 400, capture: true });

    const dialog = await openCloneDialog(page);
    await dialog.getByLabel('데이터셋 이름 *').fill('   ');
    await dialog.getByRole('button', { name: '복제' }).click();

    // 이름 칸 아래 한국어 안내가 입력에 연결되고, 서버 영어 메시지는 없다
    await expect(dialog.getByText('데이터셋 이름을 입력하세요')).toBeVisible();
    await expect(dialog.getByLabel('데이터셋 이름 *')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.getByText('must not be blank')).toHaveCount(0);
    expect(clone.requests).toHaveLength(0);
  });

  test('복제: 이름 앞뒤 공백은 잘라서 보낸다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, 1);
    const clone = await mockCloneSuccess(page);

    const dialog = await openCloneDialog(page);
    await dialog.getByLabel('데이터셋 이름 *').fill('  매출 복사본  ');
    await dialog.getByRole('button', { name: '복제' }).click();

    const req = await clone.waitForRequest();
    expect(req.payload).toMatchObject({ name: '매출 복사본' });
  });

  test('복제: 원래 이름이 길어도 기본 사본 이름은 100자 안이라 그대로 복제된다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/datasets/1', createDatasetDetail({ id: 1, name: '가'.repeat(98) }));
    const clone = await mockCloneSuccess(page);

    const dialog = await openCloneDialog(page);
    await dialog.getByRole('button', { name: '복제' }).click();

    // 자르는 셈(코드 포인트·경계)은 clone-name 단위 테스트가 고정한다 — 여기서는 기본값이 상한 안으로 폼에 들어가는지만 본다
    const req = await clone.waitForRequest();
    const name = (req.payload as { name: string }).name;
    expect(name.length).toBeLessThanOrEqual(100);
    expect(name.endsWith('_copy')).toBe(true);
  });

  test('복제: 100자를 넘는 이름은 이름 칸에 안내하고 복제 요청을 보내지 않는다', async ({ authenticatedPage: page }) => {
    await setupDatasetDetailMocks(page, 1);
    const clone = await mockApi(page, 'POST', '/api/v1/datasets/1/clone', {}, { status: 500, capture: true });

    const dialog = await openCloneDialog(page);
    await dialog.getByLabel('데이터셋 이름 *').fill('a'.repeat(101));
    await dialog.getByRole('button', { name: '복제' }).click();

    await expect(dialog.getByText('이름은 100자 이하여야 합니다')).toBeVisible();
    expect(clone.requests).toHaveLength(0);
  });
});
