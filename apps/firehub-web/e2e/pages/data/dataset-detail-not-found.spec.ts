import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 이슈 #96: 데이터셋 상세 — 존재하지 않는 ID 접근 시 무한 스켈레톤 (404 안내 부재)
 *
 * useDataset() 훅이 404로 dataset === undefined 상태가 되어도
 * 같은 스켈레톤 분기를 타서 영원히 스켈레톤이 보이던 버그.
 * isError 분기를 추가하여 toast.error + 목록으로 navigate 처리되도록 수정함.
 */
test.describe('데이터셋 상세 — 존재하지 않는 ID 처리 (#96)', () => {
  test('존재하지 않는 데이터셋 ID(404) 접근 시 toast 안내 후 목록으로 이동한다', async ({
    authenticatedPage: page,
  }) => {
    // 데이터셋 목록 페이지 모킹 (이동 후 렌더링용)
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      totalElements: 0,
      totalPages: 0,
      number: 0,
      size: 20,
    });
    // 핵심: 404 응답으로 useDataset이 isError 상태가 되도록 함
    await mockApi(
      page,
      'GET',
      '/api/v1/datasets/999999',
      { message: '데이터셋을 찾을 수 없습니다.' },
      { status: 404 }
    );

    await page.goto('/data/datasets/999999');

    // 에러 토스트가 표시되어야 한다 (스켈레톤 무한 표시 X)
    await expect(page.getByText('데이터셋을 찾을 수 없습니다.')).toBeVisible({ timeout: 5000 });

    // 데이터셋 목록 페이지로 navigate 되어야 한다
    await expect(page).toHaveURL(/\/data\/datasets$/, { timeout: 5000 });
  });
});
