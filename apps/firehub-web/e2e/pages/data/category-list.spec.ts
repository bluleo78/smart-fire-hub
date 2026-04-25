import { createCategories } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 카테고리 관리 페이지 E2E 테스트
 * - 목록 렌더링, 빈 상태, 생성 다이얼로그를 검증한다.
 */
test.describe('카테고리 관리 페이지', () => {
  test('카테고리 목록이 올바르게 렌더링된다', async ({ authenticatedPage: page }) => {
    // 카테고리 3개 목록 모킹
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());

    await page.goto('/data/categories');

    // 페이지 제목 확인
    await expect(page.getByRole('heading', { name: '카테고리 관리' })).toBeVisible();

    // 테이블 헤더 컬럼 확인
    await expect(page.getByRole('columnheader', { name: '이름' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: '설명' })).toBeVisible();

    // createCategories()의 카테고리 이름이 렌더링되는지 확인
    await expect(page.getByRole('cell', { name: '소방 데이터' })).toBeVisible();
    await expect(page.getByRole('cell', { name: '통계 데이터' })).toBeVisible();
    // exact: true 로 "기타 데이터 카테고리" 설명 셀과 구분
    await expect(page.getByRole('cell', { name: '기타', exact: true })).toBeVisible();

    // 설명 셀 렌더링 확인 — API 응답의 description 필드가 화면에 표시되는지 검증
    await expect(page.getByRole('cell', { name: '소방 관련 데이터 카테고리' })).toBeVisible();
    await expect(page.getByRole('cell', { name: '통계 분석용 데이터 카테고리' })).toBeVisible();
    await expect(page.getByRole('cell', { name: '기타 데이터 카테고리' })).toBeVisible();

    // 행 수 확인 — 헤더 행 1개 + 데이터 행 3개 = 총 4행
    const rows = page.getByRole('row');
    await expect(rows).toHaveCount(4);
  });

  test('카테고리가 없을 때 빈 상태 메시지를 표시한다', async ({ authenticatedPage: page }) => {
    // 빈 배열로 모킹
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);

    await page.goto('/data/categories');

    // 빈 상태 메시지 확인
    await expect(page.getByText('카테고리가 없습니다.')).toBeVisible();
  });

  test('새 카테고리 버튼 클릭 시 생성 다이얼로그가 열린다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);

    // POST /api/v1/dataset-categories 호출 캡처 — 생성 폼 제출 시 payload 검증에 사용
    const createCapture = await mockApi(
      page,
      'POST',
      '/api/v1/dataset-categories',
      { id: 10, name: '신규 카테고리', description: '테스트 설명' },
      { capture: true },
    );

    await page.goto('/data/categories');

    // "새 카테고리" 버튼 클릭
    await page.getByRole('button', { name: /새 카테고리/ }).click();

    // 카테고리 생성 다이얼로그가 열리는지 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('heading', { name: '카테고리 생성' })).toBeVisible();

    // 폼 입력 — 이름 및 설명 필드 채우기
    await page.getByLabel('이름').fill('신규 카테고리');
    await page.getByLabel('설명').fill('테스트 설명');

    // 생성 버튼 클릭 (다이얼로그 내 submit 버튼)
    await page.getByRole('dialog').getByRole('button', { name: '생성' }).click();

    // POST API 호출 및 payload 검증
    const req = await createCapture.waitForRequest();
    expect(req.payload).toMatchObject({
      name: '신규 카테고리',
      description: '테스트 설명',
    });
  });

  test('카테고리 생성 다이얼로그에서 취소 시 다이얼로그가 닫힌다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);

    await page.goto('/data/categories');

    // 다이얼로그 열기
    await page.getByRole('button', { name: /새 카테고리/ }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 취소 버튼 클릭
    await page.getByRole('button', { name: '취소' }).click();

    // 다이얼로그가 닫히는지 확인
    await expect(page.getByRole('dialog')).not.toBeVisible();
  });

  test('카테고리 수정 — 수정 다이얼로그에서 PUT payload 검증', async ({
    authenticatedPage: page,
  }) => {
    // 카테고리 목록 모킹 (첫 번째 카테고리 id=1)
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());

    // PUT /api/v1/dataset-categories/1 캡처 설정 — goto 이전에 등록해야 한다
    const updateCapture = await mockApi(
      page,
      'PUT',
      '/api/v1/dataset-categories/1',
      { id: 1, name: '수정된 소방 데이터', description: '수정된 설명' },
      { capture: true },
    );

    await page.goto('/data/categories');

    // 첫 번째 행의 편집(Pencil) 버튼 클릭
    const firstRow = page.getByRole('row').nth(1);
    await firstRow.getByRole('button').first().click();

    // 수정 다이얼로그가 열리는지 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('heading', { name: '카테고리 수정' })).toBeVisible();

    // 이름 필드 수정
    const nameInput = page.locator('#edit-name');
    await nameInput.clear();
    await nameInput.fill('수정된 소방 데이터');

    // 수정 버튼 클릭
    await page.getByRole('dialog').getByRole('button', { name: '수정' }).click();

    // PUT API 호출 및 payload 검증
    const req = await updateCapture.waitForRequest();
    expect(req.payload).toMatchObject({ name: '수정된 소방 데이터' });
  });

  test('카테고리 생성 — 중복 이름(409) 시 한국어 오류 토스트 표시', async ({
    authenticatedPage: page,
  }) => {
    // 카테고리 목록 모킹
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());

    // POST → 409 Conflict 응답 모킹 (백엔드 영어 메시지 포함)
    await mockApi(
      page,
      'POST',
      '/api/v1/dataset-categories',
      { status: 409, error: 'Conflict', message: 'Category name already exists: 행정' },
      { status: 409 },
    );

    await page.goto('/data/categories');

    // "새 카테고리" 버튼 클릭 → 다이얼로그 열기
    await page.getByRole('button', { name: /새 카테고리/ }).click();
    await expect(page.getByRole('dialog')).toBeVisible();

    // 중복 이름 입력 후 생성 버튼 클릭
    await page.getByLabel('이름').fill('행정');
    await page.getByRole('dialog').getByRole('button', { name: '생성' }).click();

    // 한국어 오류 토스트가 표시되어야 한다
    await expect(page.getByText('이미 사용 중인 카테고리 이름입니다.')).toBeVisible();

    // 백엔드 영어 메시지가 그대로 노출되지 않아야 한다
    await expect(page.getByText('Category name already exists')).not.toBeVisible();
  });

  test('카테고리 수정 — 중복 이름(409) 시 한국어 오류 토스트 표시', async ({
    authenticatedPage: page,
  }) => {
    // 카테고리 목록 모킹
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());

    // PUT → 409 Conflict 응답 모킹 (백엔드 영어 메시지 포함)
    await mockApi(
      page,
      'PUT',
      '/api/v1/dataset-categories/1',
      { status: 409, error: 'Conflict', message: 'Category name already exists: 운영' },
      { status: 409 },
    );

    await page.goto('/data/categories');

    // 첫 번째 행의 편집(Pencil) 버튼 클릭
    const firstRow = page.getByRole('row').nth(1);
    await firstRow.getByRole('button').first().click();

    // 수정 다이얼로그가 열리는지 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('heading', { name: '카테고리 수정' })).toBeVisible();

    // 다른 카테고리와 중복되는 이름으로 수정 후 제출
    const nameInput = page.locator('#edit-name');
    await nameInput.clear();
    await nameInput.fill('운영');
    await page.getByRole('dialog').getByRole('button', { name: '수정' }).click();

    // 한국어 오류 토스트가 표시되어야 한다
    await expect(page.getByText('이미 사용 중인 카테고리 이름입니다.')).toBeVisible();

    // 백엔드 영어 메시지가 그대로 노출되지 않아야 한다
    await expect(page.getByText('Category name already exists')).not.toBeVisible();
  });

  test('카테고리 삭제 — 삭제 확인 후 DELETE API 호출', async ({
    authenticatedPage: page,
  }) => {
    // 카테고리 목록 모킹 (첫 번째 카테고리 id=1)
    await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());

    // DELETE /api/v1/dataset-categories/1 캡처 설정
    const deleteCapture = await mockApi(
      page,
      'DELETE',
      '/api/v1/dataset-categories/1',
      {},
      { capture: true },
    );

    await page.goto('/data/categories');

    // 첫 번째 행의 삭제(Trash2) 버튼 클릭 — 편집 버튼 다음 버튼
    const firstRow = page.getByRole('row').nth(1);
    await firstRow.getByRole('button').nth(1).click();

    // AlertDialog 삭제 확인 다이얼로그가 열리는지 확인
    await expect(page.getByRole('alertdialog')).toBeVisible();

    // 확인 버튼 클릭 → DELETE API 호출
    const confirmButton = page.getByRole('alertdialog').getByRole('button', { name: /삭제|확인/ });
    await confirmButton.click();

    // DELETE API가 실제로 호출되었는지 확인
    const req = await deleteCapture.waitForRequest();
    expect(req).toBeTruthy();
  });
});
