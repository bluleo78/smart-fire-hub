import type { Page } from '@playwright/test';

import { createAuditLog } from '../../factories/admin.factory';
import { createChartListItem } from '../../factories/analytics.factory';
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

  /** 감사 로그 한 쪽 응답 — 요청한 쪽·쪽 크기·총 건수로 행을 만든다(쪽 모킹 셋이 함께 쓴다) */
  function auditPageFulfill(pageNo: number, size: number, total: number) {
    const from = pageNo * size;
    const count = Math.max(0, Math.min(size, total - from));
    const content = Array.from({ length: count }, (_, i) => createAuditLog({ id: from + i + 1, description: `로그 ${from + i + 1}` }));
    return {
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(createPageResponse(content, { page: pageNo, size, totalElements: total })),
    };
  }

  /**
   * 응답을 붙잡아 두는 문 — 모킹한 요청이 걸리면 `arrived` 가 풀리고, 테스트가 `release()` 를 부를 때까지
   * 응답을 미룬다. 되돌림 같은 짧은 창을 그대로 남겨 두고 관찰할 때 쓴다.
   */
  function held() {
    let notifyArrived: () => void = () => {};
    let notifyRelease: () => void = () => {};
    const arrived = new Promise<void>((resolve) => {
      notifyArrived = () => resolve();
    });
    const released = new Promise<void>((resolve) => {
      notifyRelease = () => resolve();
    });
    return {
      arrived,
      release: () => notifyRelease(),
      block: () => {
        notifyArrived();
        return released;
      },
    };
  }

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
        return route.fulfill(auditPageFulfill(pageNo, size, total));
      },
    );
  }

  /** 감사 로그 API 를 총 건수가 줄어든 상태로 응답한다 — 2쪽(page>=1)을 요청한 순간부터 모든 쪽이 3건, 그 전(1쪽=page 0)은 25건 */
  async function mockAuditShrinking(page: Page) {
    let shrunk = false;
    await page.route(
      (url) => url.pathname === '/api/v1/admin/audit-logs',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const sp = new URL(route.request().url()).searchParams;
        const pageNo = Number(sp.get('page') ?? '0');
        // 2쪽 이상을 요청한 순간 데이터가 줄어들었다고 본다 — 줄어든 뒤에는 1쪽도 같은 건수로 준다
        if (pageNo >= 1) shrunk = true;
        const total = shrunk ? 3 : 25;
        const size = Number(sp.get('size') ?? '20');
        return route.fulfill(auditPageFulfill(pageNo, size, total));
      },
    );
  }

  /** 차트 목록 API 를 쪽마다 갈라 응답한다 — page 2 요청부터 총 15건으로 줄어, 쪽 되돌림이 없는 화면에 범위 밖 쪽이 그대로 머무르게 한다 */
  async function mockChartsShrinkingAfterPage1(page: Page) {
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/charts',
      (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const pageNo = Number(new URL(route.request().url()).searchParams.get('page') ?? '0');
        // page 0·1 은 25건(3쪽), page 2 이상은 15건(2쪽) — 마지막 쪽을 누르면 page 2 를 받아 범위 밖이 된다
        const total = pageNo < 2 ? 25 : 15;
        const from = pageNo * 10;
        const count = Math.max(0, Math.min(10, total - from));
        const content = Array.from({ length: count }, (_, i) =>
          createChartListItem({ id: from + i + 1, name: `차트 ${from + i + 1}` }),
        );
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(content, { page: pageNo, size: 10, totalElements: total })),
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

  test('범위 밖 쪽이 되어도 마지막 유효 쪽으로 되돌아오고 건수는 리전 안에서 낭독된다 (WD-983)', async ({ authenticatedPage: page }) => {
    await mockAuditShrinking(page);
    await page.goto('/admin/audit-logs');
    // 기본 20건 → 쪽 크기 10 으로 바꿔 25건이 3쪽이 되게 한다
    await page.getByRole('combobox', { name: '페이지당' }).click();
    await page.getByRole('option', { name: '10', exact: true }).click();
    await expect(announcedCount(page, '총 25건 중 1-10')).toBeVisible();

    // 「마지막 페이지」를 누르기 전에 빈 안내 관찰자를 붙인다 — 되돌리는 동안(범위 밖 응답 도착 → 되돌림 → 재조회)에
    // 표 지역에 새로 추가되는 「감사 로그가 없습니다」 계열 노드를 전부 기록한다. 끝 상태만 보는 단언은 응답 도착과
    // 되돌림 사이 한 커밋의 일시 빈 안내(content=[] 이 그려진 한 프레임)를 놓치지만, 이 관찰자는 그림 타이밍과 무관하게
    // DOM 에 추가되는 순간 자체를 보므로 그 한 커밋이라도 잡아낸다(holdRequests 로는 못 잡는다 — 그 짧은 틈에 대기할
    // 네트워크 요청이 없기 때문이다).
    await page.evaluate(() => {
      const w = window as unknown as { __emptyAdds: string[] };
      w.__emptyAdds = [];
      // 「조건에 맞는 감사 로그가 없습니다」는 앞의 문구를 포함하므로 하나의 부분 문자열로 함께 잡는다
      const pattern = /감사 로그가 없습니다/;
      new MutationObserver((records) => {
        for (const r of records) {
          // childList 는 새 행/노드 삽입, characterData 는 기존 텍스트 노드의 문구 바꿈을 각각 잡는다
          const nodes = r.type === 'characterData' ? [r.target] : Array.from(r.addedNodes);
          for (const n of nodes) {
            if (!(n instanceof Node)) continue;
            const text = n.textContent ?? '';
            // aria-hidden 이든 아니든 빈 안내 문구가 그려지는 자체가 문제 — 숨김 상태로 그려져도 기록한다
            if (pattern.test(text)) w.__emptyAdds.push(text.trim().slice(0, 200));
          }
        }
      }).observe(document.body, { childList: true, subtree: true, characterData: true });
    });

    await page.getByRole('button', { name: '마지막 페이지' }).click();

    // 되돌아온 쪽(0쪽)에서 건수가 리전 안에서 낭독된다
    await expect(announcedCount(page, '총 3건 중 1-3')).toBeVisible();
    // 되돌리는 동안 기록된 빈 안내 노드는 하나도 없다 — 위 관찰자가 되돌림 구간의 일시 커밋을 그림 타이밍과 무관하게
    // 봤으므로, 빈 안내가 한 번이라도 뜬 적이 있으면 여기에 남는다
    await expect
      .poll(() => page.evaluate(() => (window as unknown as { __emptyAdds: string[] }).__emptyAdds), {
        message: '되돌리는 동안 표에 빈 안내 노드가 추가되면 안 된다',
      })
      .toEqual([]);
    // 되돌리는 동안/뒤에 빈 안내가 남아 있지 않다 — 빈 결과로 오해하지 않게
    await expect(page.getByRole('table').getByRole('status')).toHaveCount(0);
    // 범위 밖 건수는 화면 어디에도 없다
    await expect(plainCount(page, '총 3건')).toHaveCount(0);
  });

  test('되돌아온 쪽이 줄어들기 전 총 건수(25건)를 낭독 리전에 두지 않는다 (WD-983 반려)', async ({ authenticatedPage: page }) => {
    // 되돌림 직후 되돌아간 쪽의 재조회를 잡아 두어 「낡은 건수가 남아 있는 창」 자체를 관찰한다.
    // 잡지 않으면 재조회가 목록 하나로 그 창을 지나가 버려, 낡은 낭독이 그림 타이밍에 가려 드러나지 않는다.
    let shrunk = false;
    let holdPage0 = false;
    const outOfRangePage = held();
    const rollbackPage = held();

    await page.route(
      (url) => url.pathname === '/api/v1/admin/audit-logs',
      async (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const sp = new URL(route.request().url()).searchParams;
        const pageNo = Number(sp.get('page') ?? '0');
        const size = Number(sp.get('size') ?? '20');
        if (pageNo >= 1) {
          // 2쪽 이상을 요청한 순간 총 건수가 줄었다고 본다 — 줄어든 뒤에는 1쪽도 같은 값으로 준다
          shrunk = true;
          await outOfRangePage.block();
        } else if (holdPage0) {
          // 되돌아간 쪽의 재조회 — 응답을 붙잡아 두어 낡은 건수가 남아 있으면 그대로 보이게 한다
          await rollbackPage.block();
        }
        return route.fulfill(auditPageFulfill(pageNo, size, shrunk ? 3 : 25));
      },
    );

    await page.goto('/admin/audit-logs');
    // 기본 20건 → 쪽 크기 10 으로 바꿔 25건이 3쪽이 되게 한다
    await page.getByRole('combobox', { name: '페이지당' }).click();
    await page.getByRole('option', { name: '10', exact: true }).click();
    await expect(announcedCount(page, '총 25건 중 1-10')).toBeVisible();

    await page.getByRole('button', { name: '마지막 페이지' }).click();
    await outOfRangePage.arrived;
    // 되돌아갈 쪽의 재조회를 잡아 둔 뒤에야 범위 밖 응답을 흘려보낸다 — 되돌림이 일어나는 창이 그대로 남는다
    holdPage0 = true;
    outOfRangePage.release();
    await rollbackPage.arrived;

    // 줄어들기 전 총 건수(25)가 되돌아온 쪽에 다시 나오면 안 된다 — 낡은 건수 낭독이 곧 이슈의 결함이다
    await expect(page.locator('[role="status"]').filter({ hasText: '총 25건' })).toHaveCount(0);
    // 캐시가 비워져 자리는 범위 밖 응답이 준 줄어든 뒤 값(3건)으로 채워진다
    await expect(announcedCount(page, '총 3건 중 1-3')).toBeVisible();
    // 되돌아온 쪽은 빈 목록으로 잠깐이라도 뜨지 않는다 — 스켈레톤이 빈 안내를 대신한다
    await expect(page.getByRole('table').getByRole('status')).toHaveCount(0);
    // 줄어든 뒤 totalPages 가 이미 1이라 방향 버튼 nav 는 없고 사이즈 selector 만 남는다 —
    // 줄어들기 전(3쪽) 캐시가 남아 있으면 nav 가 그대로 있으므로 이것도 낡은 캐시가 없어졌다는 근거다.
    // (이 시점의 포커스는 방향 버튼이 사라지면서 body 로 간다 — 1쪽이라 옮길 쪽이 없어 nav 를 그리지 않는 것이 맞다)
    await expect(page.getByRole('navigation', { name: '페이지네이션' })).toHaveCount(0);

    rollbackPage.release();
    await expect(announcedCount(page, '총 3건 중 1-3')).toBeVisible();
    await expect(page.getByRole('table').getByRole('status')).toHaveCount(0);
    // 되돌아온 쪽은 마지막 유효 쪽(1쪽)이고 표에는 줄어든 뒤 남은 3행이 있다
    await expect(page.getByRole('table').getByRole('cell', { name: '로그 1' })).toBeVisible();
  });

  test('범위 밖 쪽이면 「총 15건」 은 라이브 리전에 두지 않는다 — 되돌리기 없는 화면(차트 목록)에서 재현 (WD-931)', async ({
    authenticatedPage: page,
  }) => {
    // 왜 이 화면인가: 감사 로그·사용자·데이터셋 목록에는 쪽 되돌리기가 생겨(또는 이미 있어) 범위 밖 쪽이 머무르지 않는다.
    // 공용 SimplePagination 의 "범위 밖 건수는 낭독 리전 밖" 계약(WD-931)은 쪽 되돌림이 없는 화면에서만 재현된다 —
    // 차트 목록(ChartListPage)이 그 화면이다(size 고정 10, 되돌리기 없음, SimplePagination 에 totalElements·pageSize 전달).
    await mockChartsShrinkingAfterPage1(page);
    await page.goto('/analytics/charts');
    await expect(announcedCount(page, '총 25건 중 1-10')).toBeVisible();

    await page.getByRole('button', { name: '마지막 페이지' }).click();

    // 범위 밖 쪽(2쪽, 총 15건) — 되돌리기가 없어 그대로 머문다.
    // 범위 밖이라 「중 a-b」가 붙지 않고 「총 15건」으로만 그려진다(계획 Review Focus 4 — hasText 는 부분 일치라 이 형태로만 잡는다)
    await expect(plainCount(page, '총 15건')).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: '총 15건' })).toHaveCount(0);
  });

  test('쪽을 넘기면 건수 범위가 role=status 영역 안에서 바뀐다', async ({ authenticatedPage: page }) => {
    await mockAuditByResult(page, { total: 25 });
    await page.goto('/admin/audit-logs');
    // 쪽 크기를 10 으로 바꿔 25건을 3쪽으로 나눈다
    await page.getByRole('combobox', { name: '페이지당' }).click();
    await page.getByRole('option', { name: '10', exact: true }).click();
    await expect(announcedCount(page, '총 25건 중 1-10')).toBeVisible();

    await page.getByRole('button', { name: '다음 페이지' }).click();
    await expect(announcedCount(page, '총 25건 중 11-20')).toBeVisible();
  });
});
