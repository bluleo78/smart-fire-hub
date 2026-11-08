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

/**
 * 이슈 WD-568: 데이터셋 상세 — 공백(`%20`)·`0` id 접근 시 무한 로딩 스켈레톤에 갇힘
 *
 * `Number(' ')`·`Number('0')` 은 0 이라 #543 의 `Number.isFinite` 검사를 통과했고,
 * datasetId=0 이면 useDataset 의 `enabled: !!id` 가 false 라 쿼리가 돌지 않아 스켈레톤이 영원히 남았다.
 * 숫자만·양의 정수만 받는 parseRouteId 로 읽어 기존 「없는 id」 처리(토스트 + 목록 이동)를 타게 했다.
 */
test.describe('데이터셋 상세 — 공백·0 ID 처리 (WD-568)', () => {
  for (const rawId of ['%20', '0']) {
    test(`ID "${rawId}"(/data/datasets/${rawId}) 접근 시 무한 스켈레톤 대신 목록으로 이동한다`, async ({
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

      // 잘못된 id 로 상세(및 하위) API 를 부르지 않아야 한다
      const detailPath = new RegExp(`^/api/v1/datasets/${rawId}(/|$)`);
      let datasetDetailRequested = false;
      await page.route(
        (url) => detailPath.test(url.pathname),
        (route) => {
          datasetDetailRequested = true;
          return route.abort();
        },
      );

      await page.goto(`/data/datasets/${rawId}`);

      await expect(page.getByText('데이터셋을 찾을 수 없습니다.').first()).toBeVisible({
        timeout: 5000,
      });
      await expect(page).toHaveURL(/\/data\/datasets$/, { timeout: 5000 });

      expect(datasetDetailRequested).toBe(false);
    });
  }
});
