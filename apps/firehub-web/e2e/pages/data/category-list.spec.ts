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

    await page.goto('/data/categories');

    // "새 카테고리" 버튼 클릭
    await page.getByRole('button', { name: /새 카테고리/ }).click();

    // 카테고리 생성 다이얼로그가 열리는지 확인
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('heading', { name: '카테고리 생성' })).toBeVisible();
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
});
