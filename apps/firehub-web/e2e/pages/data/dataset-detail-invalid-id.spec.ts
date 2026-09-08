import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 이슈 #543: 데이터셋 상세 — 비숫자 ID(/data/datasets/abc) 접근 시 무한 로딩 스켈레톤에 갇힘
 *
 * 근본 원인: `Number('abc')` = NaN이 datasetId로 그대로 쓰이고,
 * useDataset의 `enabled: !!id`(!!NaN === false)로 쿼리 자체가 실행되지 않아
 * isLoading/isError가 영구히 false로 남아 기존 404 처리(#96)를 우회했다.
 * #545(스마트 작업 상세)와 동일한 원인 클래스라 같은 isInvalidId 플래그 패턴으로 수정함.
 */
test.describe('데이터셋 상세 — 비숫자 ID 처리 (#543)', () => {
  test('비숫자 ID(/data/datasets/abc) 접근 시 무한 스켈레톤 대신 목록으로 이동한다', async ({
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

    // 핵심 검증: 비숫자 ID이므로 상세 조회 API가 아예 호출되지 않아야 한다
    let datasetDetailRequested = false;
    await page.route('**/api/v1/datasets/abc', () => {
      datasetDetailRequested = true;
    });

    await page.goto('/data/datasets/abc');

    // 에러 토스트가 표시되어야 한다 (스켈레톤 무한 표시 X)
    await expect(page.getByText('데이터셋을 찾을 수 없습니다.').first()).toBeVisible({
      timeout: 5000,
    });

    // 데이터셋 목록 페이지로 navigate 되어야 한다
    await expect(page).toHaveURL(/\/data\/datasets$/, { timeout: 5000 });

    expect(datasetDetailRequested).toBe(false);
  });
});
