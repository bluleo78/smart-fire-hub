import type { Page } from '@playwright/test';

import { createAuditLog } from '../../factories/admin.factory';
import { createUser } from '../../factories/auth.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 표 빈 상태 라이브 리전 E2E 회귀 (WD-815)
 * - 공유 TableEmptyRow 는 셀 안에 role=status(aria-live=polite) 한 개를 두고, 안내 문구는 바로 그리되 잠시(ANNOUNCE_DELAY_MS) 뒤에 접근성 트리에 드러낸다.
 *   그래야 "결과 있음 → 검색 0건" 전환 때 스크린리더가 문구를 읽는다. 화면 두 곳에서 그 계약을 확인한다.
 * - 관리 화면 권한 가드 통과를 위해 setupAdminAuth 로 ADMIN 사용자를 쓴다.
 */
test.describe('표 빈 상태 라이브 리전 (WD-815)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  /** 사용자 목록 API 를 쿼리 search 로 갈라 응답한다(화면 URL 의 q 가 API 에서는 search) — 비면 2명, 있으면 빈 페이지. 요청 search 값을 캡처한다. */
  async function mockUsersByQuery(page: Page) {
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

    // 표 안 status 에 「숨기지 않은 노드」가 실제로 추가되는지 기록한다 — 문구가 처음부터 보이는 채 aria-hidden 만 떼면
    // 일부 화면 읽기 프로그램이 낭독하지 않으므로, 노드 추가로 안내하는 계약을 지킨다
    await page.evaluate(() => {
      const w = window as unknown as { __statusAdds: string[] };
      w.__statusAdds = [];
      new MutationObserver((records) => {
        for (const r of records) {
          for (const n of Array.from(r.addedNodes)) {
            // 이미 있던 status 안에 추가된 노드만 센다(행째 새로 마운트된 status 는 「내용과 함께 생성」이라 세지 않는다)
            const inStatus = r.target instanceof Element && r.target.closest('[role="status"]');
            const hidden = r.target instanceof Element && r.target.closest('[aria-hidden="true"]');
            const selfHidden = n instanceof Element && n.getAttribute('aria-hidden') === 'true';
            if (inStatus && !hidden && !selfHidden) w.__statusAdds.push(n.textContent ?? '');
          }
        }
      }).observe(document.body, { childList: true, subtree: true });
    });

    // 검색 입력 → 디바운스 뒤 요청 q 가 입력값
    const keyword = '존재하지않는사용자xyz';
    await page.getByPlaceholder('이름 또는 아이디로 검색...').fill(keyword);
    await expect.poll(() => queries[queries.length - 1]).toBe(keyword);

    // 표 안 status 가 안내 문구를 담는다(두 <p> 가 공백 없이 이어짐)
    const status = page.getByRole('table').getByRole('status');
    await expect(status).toHaveText(`'${keyword}'에 대한 결과가 없습니다.다른 키워드로 검색해 보세요.`);
    await expect(status).toHaveAttribute('aria-live', 'polite');
    // 지연 뒤에는 문구가 접근성 트리에 드러나 있고, 숨기지 않은 문구 노드가 status 안에 새로 추가됐다
    await expect(status.locator('[aria-hidden="true"]')).toHaveCount(0);
    await expect
      .poll(() => page.evaluate(() => (window as unknown as { __statusAdds: string[] }).__statusAdds.join('|')))
      .toContain(keyword);

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

/**
 * 목록 건수 라이브 리전 (WD-931)
 * - 공유 SimplePagination 의 「총 N건 중 a-b」가 role=status 안에 있어, 필터·쪽 이동으로 건수가 바뀌면 스크린리더가 알린다.
 * - 감사 로그는 필터 재조회 중 페이지네이션이 언마운트됐다 다시 마운트되므로 DelayedStatus(WD-815)로 감싼 것을 확인한다.
 */
test.describe('WD-931 목록 건수 라이브 리전', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  /** 결과 필터별 총 건수 — 필터 없으면 4건 */
  const TOTAL_BY_RESULT: Record<string, number> = { SUCCESS: 3, FAILURE: 0 };

  /** 낭독되는 건수 노드 — role=status 리전 안에서 aria-hidden 이 아닌 노드(DelayedStatus 지연 뒤의 노드)만 잡는다 */
  function announcedCount(page: Page, text: string) {
    return page.locator('[role="status"] > :not([aria-hidden="true"])').filter({ hasText: text });
  }

  /** 리전 밖 건수 — role=status 조상이 없는 일반 문구만 잡는다 */
  function plainCount(page: Page, text: string) {
    return page.locator('p:not([role="status"] *)').filter({ hasText: text });
  }

  /** 결과 필터 드롭다운에서 옵션(예: 「실패」)을 고른다 */
  async function selectResult(page: Page, name: string) {
    await page.getByLabel('결과 필터').click();
    await page.getByRole('option', { name }).click();
  }

  /** 감사 로그 화면을 열고 필터 없는 기본 건수 「총 4건 중 1-4」 가 뜰 때까지 기다린다 */
  async function openAuditLogs(page: Page) {
    await mockAuditByResult(page);
    await page.goto('/admin/audit-logs');
    await expect(announcedCount(page, '총 4건 중 1-4')).toBeVisible();
  }

  /** 감사 로그 API 를 result 필터·page 로 갈라 응답한다 — 건수는 TOTAL_BY_RESULT(기본 4건), opts.total, opts.totalForPage(쪽별 총 건수) 순으로 우선 */
  async function mockAuditByResult(page: Page, opts: { total?: number; totalForPage?: (pageNo: number) => number } = {}) {
    await page.route(
      (url) => url.pathname === '/api/v1/admin/audit-logs',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const sp = new URL(route.request().url()).searchParams;
        const result = sp.get('result');
        const pageNo = Number(sp.get('page') ?? '0');
        const total = opts.totalForPage?.(pageNo) ?? opts.total ?? TOTAL_BY_RESULT[result ?? ''] ?? 4;
        // 화면 기본 쪽 크기는 20 — 요청의 size 를 그대로 따른다
        const size = Number(sp.get('size') ?? '20');
        const from = pageNo * size;
        const count = Math.max(0, Math.min(size, total - from));
        const content = Array.from({ length: count }, (_, i) => createAuditLog({ id: from + i + 1, description: `로그 ${from + i + 1}` }));
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(content, { page: pageNo, size, totalElements: total })),
        });
      },
    );
  }

  test('필터로 결과가 바뀌면 건수가 role=status 영역 안에서 새 건수로 바뀐다', async ({ authenticatedPage: page }) => {
    await openAuditLogs(page);

    await selectResult(page, '성공');

    await expect(announcedCount(page, '총 3건 중 1-3')).toBeVisible();
  });

  test('0건에서 결과가 돌아오면 건수가 role=status 영역 안에 다시 담긴다', async ({ authenticatedPage: page }) => {
    await openAuditLogs(page);

    await selectResult(page, '실패');
    await expect(page.getByRole('table').getByRole('status')).toHaveText('조건에 맞는 감사 로그가 없습니다.');

    await selectResult(page, '성공');
    await expect(announcedCount(page, '총 3건 중 1-3')).toBeVisible();
  });

  test('0건이면 「총 0건」 은 라이브 리전에 두지 않는다 — 표의 빈 안내가 이미 낭독된다', async ({ authenticatedPage: page }) => {
    await openAuditLogs(page);

    await selectResult(page, '실패');
    await expect(page.getByRole('table').getByRole('status')).toHaveText('조건에 맞는 감사 로그가 없습니다.');

    // 글자는 보이되, 「총 0건」 을 담은 status 는 없다(빈 안내와 겹쳐 두 번 읽히지 않게)
    await expect(plainCount(page, '총 0건')).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: '총 0건' })).toHaveCount(0);
  });

  test('범위 밖 쪽이면 「총 3건」 은 라이브 리전에 두지 않는다 — 어긋난 범위·건수가 낭독되지 않게', async ({ authenticatedPage: page }) => {
    // 3쪽(page=2)만 총 3건으로 줄어든 응답을 준다 — 앞 쪽은 25건이라 3쪽 이동이 가능하고, 이동하면 start(20) >= 총 3건 이 된다
    await mockAuditByResult(page, { totalForPage: (pageNo) => (pageNo >= 2 ? 3 : 25) });
    await page.goto('/admin/audit-logs');
    await page.getByRole('combobox', { name: '페이지 사이즈' }).click();
    await page.getByRole('option', { name: '10', exact: true }).click();
    await expect(announcedCount(page, '총 25건 중 1-10')).toBeVisible();

    await page.getByRole('button', { name: '마지막 페이지' }).click();

    // 글자는 보이되 role=status 리전 안이 아니다
    await expect(plainCount(page, '총 3건')).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: '총 3건' })).toHaveCount(0);
  });

  test('쪽을 넘기면 건수 범위가 role=status 영역 안에서 바뀐다', async ({ authenticatedPage: page }) => {
    await mockAuditByResult(page, { total: 25 });
    await page.goto('/admin/audit-logs');
    // 쪽 크기를 10 으로 바꿔 25건을 3쪽으로 나눈다
    await page.getByRole('combobox', { name: '페이지 사이즈' }).click();
    await page.getByRole('option', { name: '10', exact: true }).click();
    await expect(announcedCount(page, '총 25건 중 1-10')).toBeVisible();

    await page.getByRole('button', { name: '다음 페이지' }).click();
    await expect(announcedCount(page, '총 25건 중 11-20')).toBeVisible();
  });
});
