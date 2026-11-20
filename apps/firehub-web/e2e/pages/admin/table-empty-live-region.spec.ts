import { createUser } from '../../factories/auth.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 표 빈 상태 라이브 리전 E2E 회귀 (WD-815)
 * - 공유 TableEmptyRow 는 셀 안에 role=status(aria-live=polite) 한 개를 두고, 안내 문구를 잠시(ANNOUNCE_DELAY_MS) 뒤에 채운다.
 *   그래야 "결과 있음 → 검색 0건" 전환 때 스크린리더가 문구를 읽는다. 화면 두 곳에서 그 계약을 확인한다.
 * - 관리 화면 권한 가드 통과를 위해 setupAdminAuth 로 ADMIN 사용자를 쓴다.
 */
test.describe('표 빈 상태 라이브 리전 (WD-815)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  /** 사용자 목록 API 를 쿼리 search 로 갈라 응답한다(화면 URL 의 q 가 API 에서는 search) — 비면 2명, 있으면 빈 페이지. 요청 search 값을 캡처한다. */
  async function mockUsersByQuery(page: import('@playwright/test').Page) {
    const queries: string[] = [];
    await page.route(
      (url) => url.pathname === '/api/v1/users',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const q = new URL(route.request().url()).searchParams.get('search') ?? '';
        queries.push(q);
        const content = q
          ? []
          : [
              createUser({ id: 1, name: '김철수', username: 'chulsoo' }),
              createUser({ id: 2, name: '이영희', username: 'younghee' }),
            ];
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(content)),
        });
      },
    );
    return queries;
  }

  test('사용자 관리 — 결과 2건에서 검색 0건이 되면 표 안 status 가 안내 문구를 담는다', async ({ authenticatedPage: page }) => {
    const queries = await mockUsersByQuery(page);
    await page.goto('/admin/users');

    // 결과가 있을 때는 빈 상태 status 가 없다(행은 이름 링크로 상세에 간다 — WD-816)
    await expect(page.getByRole('link', { name: '김철수', exact: true })).toBeVisible();
    await expect(page.getByRole('table').getByRole('status')).toHaveCount(0);

    // 검색 입력 → 디바운스 뒤 요청 q 가 입력값
    const keyword = '존재하지않는사용자xyz';
    await page.getByPlaceholder('이름 또는 아이디로 검색...').fill(keyword);
    await expect.poll(() => queries[queries.length - 1]).toBe(keyword);

    // 표 안 status 가 안내 문구를 담는다(두 <p> 가 공백 없이 이어짐)
    const status = page.getByRole('table').getByRole('status');
    await expect(status).toHaveText(`'${keyword}'에 대한 결과가 없습니다.다른 키워드로 검색해 보세요.`);
    await expect(status).toHaveAttribute('aria-live', 'polite');

    // 초기화 버튼은 status 밖에서 보인다
    await expect(page.getByRole('button', { name: '검색 초기화' })).toBeVisible();
    await expect(status.getByRole('button')).toHaveCount(0);
  });

  test('사용자 관리 — 0건인 채 키워드를 바꾸면 status 문구가 새 키워드로 바뀐다', async ({ authenticatedPage: page }) => {
    const queries = await mockUsersByQuery(page);
    await page.goto('/admin/users');
    await expect(page.getByRole('link', { name: '김철수', exact: true })).toBeVisible();

    const search = page.getByPlaceholder('이름 또는 아이디로 검색...');
    await search.fill('존재하지않는사용자xyz');
    const status = page.getByRole('table').getByRole('status');
    await expect(status).toContainText("'존재하지않는사용자xyz'");

    // 0건 상태 그대로 키워드만 변경 — status 는 여전히 1개이고 문구만 갱신
    await search.fill('xyz2');
    await expect.poll(() => queries[queries.length - 1]).toBe('xyz2');
    await expect(page.getByRole('table').getByRole('status')).toHaveCount(1);
    await expect(status).toContainText("'xyz2'");
    await expect(status).not.toContainText('존재하지않는사용자xyz');
  });

  test('감사 로그 — 날짜 필터 빈 결과 안내도 status 로 알린다', async ({ authenticatedPage: page }) => {
    // 요청 쿼리를 캡처해 날짜 입력이 API 파라미터(startDate)로 실렸는지 확인한다
    const capture = await mockApi(page, 'GET', '/api/v1/admin/audit-logs', createPageResponse([]), { capture: true });
    await page.goto('/admin/audit-logs');

    // 기본 빈 상태 문구도 표 안 status 에 담긴다
    await expect(page.getByRole('table').getByRole('status')).toHaveText('감사 로그가 없습니다.');

    const START_DATE = '2026-09-01';
    await page.getByLabel('시작 날짜').fill(START_DATE);
    // 화면은 브라우저 로컬 자정을 UTC ISO(Z)로 바꿔 보낸다(#164·WD-11, toIsoDateTime) — KST 에서는 날짜 부분이
    // 전날(2026-08-31T15:00Z)이 되므로, 같은 상수의 로컬 자정을 같은 브라우저에서 계산해 비교한다(WD-850).
    const expectedStart = await page.evaluate((date) => {
      const [y, m, d] = date.split('-').map(Number);
      return new Date(y, m - 1, d).toISOString();
    }, START_DATE);
    await expect
      .poll(() => capture.lastRequest()?.searchParams.get('startDate') ?? '')
      .toBe(expectedStart);
    await expect(page.getByRole('table').getByRole('status')).toHaveText('조건에 맞는 감사 로그가 없습니다.');
  });
});
