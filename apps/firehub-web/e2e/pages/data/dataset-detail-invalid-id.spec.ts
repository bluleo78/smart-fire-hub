import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 이슈 #543: 데이터셋 상세 — 비숫자 ID(/data/datasets/abc) 접근 시 무한 로딩 스켈레톤에 갇힘
 *
 * 근본 원인: `Number('abc')` = NaN이 datasetId로 그대로 쓰이고,
 * useDataset의 `enabled: !!id`(!!NaN === false)로 쿼리 자체가 실행되지 않아
 * isLoading/isError가 영구히 false로 남아 기존 404 처리(#96)를 우회했다.
 * #545(스마트 작업 상세)와 동일한 원인 클래스라 같은 isInvalidId 플래그 패턴으로 수정함.
 * WD-581: 목록 이동 대신 주소를 그대로 두고 화면 안 안내 + 「목록으로」를 보인다(토스트 없음).
 *   양의 정수가 아닌 id 전부('1.5'는 400 후 목록 이동, '0'은 무한 스켈레톤이었다)를 요청 없이 같은 안내로 다룬다.
 */
test.describe('데이터셋 상세 — 양의 정수가 아닌 ID 처리 (#543, WD-581)', () => {
  for (const rawId of ['abc', '1.5', '0', '3x']) {
    test(`양의 정수가 아닌 ID(/data/datasets/${rawId})는 요청 없이 화면 안 안내를 보인다`, async ({
      authenticatedPage: page,
    }) => {
      // 「목록으로」 뒤에 그려질 데이터셋 목록
      await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
      await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
      await mockApi(page, 'GET', '/api/v1/datasets', { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 });

      // 상세 조회(/api/v1/datasets/<rawId> 및 숫자로 잘린 /api/v1/datasets/3 등)가 나가지 않아야 한다
      const detailRequests: string[] = [];
      await page.route(
        (url) => /^\/api\/v1\/datasets\/[^/]+$/.test(url.pathname) && url.pathname !== '/api/v1/datasets/tags',
        (route) => {
          detailRequests.push(new URL(route.request().url()).pathname);
          return route.fulfill({ status: 400, contentType: 'application/json', body: '{}' });
        },
      );

      await page.goto(`/data/datasets/${rawId}`);

      await expect(page.getByRole('heading', { name: '데이터셋을 찾을 수 없습니다.' })).toBeVisible();
      expect(new URL(page.url()).pathname).toBe(`/data/datasets/${rawId}`);
      await expect(page.locator('[data-sonner-toast]')).toHaveCount(0);
      expect(detailRequests).toEqual([]);

      await page.getByRole('button', { name: '목록으로' }).click();
      await expect(page).toHaveURL(/\/data\/datasets$/);
    });
  }
});
