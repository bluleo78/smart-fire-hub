import type { Locator, Page } from '@playwright/test';

import { createAuditLog, createAuditLogs } from '../../factories/admin.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { failPageRequests, holdRequests } from '../../fixtures/hold-api';
import { expectOfflineNoticeExposed, isUnderInert } from '../../fixtures/inert-a11y';

/** 대화상자가 「열리지 않는다」를 확인하는 시간(ms) — 양성 대조에서 같은 클릭은 이보다 훨씬 빨리 연다. 중복 요청 창(DUPLICATE_WINDOW_MS)과는 뜻이 달라 따로 둔다 */
const DIALOG_STAYS_CLOSED_MS = 600;

/** 대화상자가 DIALOG_STAYS_CLOSED_MS 동안 계속 0 개인지 50ms 간격으로 센다(WD-988) — 한 번 세기는 그려지기 전에 통과할 수 있다 */
async function expectStaysClosed(page: Page, dialog: Locator) {
  const message = '이동 중 이전 행 클릭으로 대화상자가 열리면 안 된다';
  const deadline = Date.now() + DIALOG_STAYS_CLOSED_MS;
  while (Date.now() < deadline) {
    expect(await dialog.count(), message).toBe(0);
    await page.waitForTimeout(50);
  }
  // 마지막 대기 뒤에도 한 번 더 센다 — 창 끝에 열린 대화상자를 놓치지 않게
  expect(await dialog.count(), message).toBe(0);
}

/**
 * 감사 로그 페이지의 강화된 페이지네이션 검증 (이슈 #65)
 * - 페이지 번호 버튼이 노출되는지
 * - 처음/마지막 점프 버튼이 동작하는지
 * - 페이지 사이즈 selector 가 API 파라미터에 반영되는지
 * - 첫 페이지에서 처음/이전 비활성, 마지막에서 다음/마지막 비활성
 */
test.describe('감사 로그 페이지네이션 - 강화 (이슈 #65)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('페이지 번호 버튼이 렌더링되고 클릭 시 page 파라미터가 전달된다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    // 390건 / 20건/page = 20 페이지
    const logs = Array.from({ length: 20 }, (_, i) => createAuditLog({ id: i + 1 }));
    const capture = await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse(logs, { totalElements: 390, totalPages: 20 }),
      { capture: true },
    );
    await page.goto('/admin/audit-logs');

    // 페이지네이션 nav 노출 확인
    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    await expect(nav).toBeVisible();

    // 1 페이지 버튼 (현재) 와 마지막 20 페이지 버튼 둘 다 노출
    await expect(nav.getByRole('button', { name: '1 페이지' })).toBeVisible();
    await expect(nav.getByRole('button', { name: '20 페이지' })).toBeVisible();

    // 마지막 페이지 점프 버튼 클릭
    await nav.getByRole('button', { name: '마지막 페이지' }).click();

    // API 가 page=19 로 호출됐는지 검증 — 재호출될 때까지 기다린다(WD-257)
    await expect.poll(() => capture.lastRequest()?.searchParams.get('page')).toBe('19');
  });

  test('페이지 사이즈 selector 변경 시 size 파라미터가 변경되고 page 가 0 으로 리셋된다', async ({
    authenticatedPage: page,
  }) => {
    const logs = Array.from({ length: 20 }, (_, i) => createAuditLog({ id: i + 1 }));
    const capture = await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse(logs, { totalElements: 390, totalPages: 20 }),
      { capture: true },
    );
    await page.goto('/admin/audit-logs');

    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    await expect(nav).toBeVisible();

    // 먼저 마지막 페이지로 점프 (page=19)
    await nav.getByRole('button', { name: '마지막 페이지' }).click();
    await expect.poll(() => capture.lastRequest()?.searchParams.get('page')).toBe('19');

    // 페이지 사이즈를 50으로 변경
    const sizeSelector = page.getByRole('combobox', { name: '페이지당' });
    await sizeSelector.click();
    await page.getByRole('option', { name: '50' }).click();

    // size=50 으로 재호출될 때까지 기다린 뒤, 같은 요청에서 page 가 0 으로 리셋됐는지 확인한다(WD-257)
    await expect.poll(() => capture.lastRequest()?.searchParams.get('size')).toBe('50');
    expect(capture.lastRequest()?.searchParams.get('page')).toBe('0');
  });

  test('첫 페이지에서 처음/이전 버튼이 비활성화된다', async ({ authenticatedPage: page }) => {
    const logs = Array.from({ length: 20 }, (_, i) => createAuditLog({ id: i + 1 }));
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse(logs, { totalElements: 390, totalPages: 20, page: 0 }),
    );
    await page.goto('/admin/audit-logs');

    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    await expect(nav.getByRole('button', { name: '처음 페이지' })).toBeDisabled();
    await expect(nav.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
    // 다음 / 마지막 은 활성
    await expect(nav.getByRole('button', { name: '다음 페이지' })).toBeEnabled();
    await expect(nav.getByRole('button', { name: '마지막 페이지' })).toBeEnabled();
  });
});

/**
 * 페이지 이동 중에도 목록·페이지네이션을 유지한다 (WD-932)
 * 예전에는 이동 중 data 가 undefined 라 SimplePagination 이 언마운트됐다 다시 그려져 포커스가 body 로 떨어졌다.
 */
test.describe('감사 로그 페이지네이션 — 이동 중에도 목록을 유지해 포커스가 남는다 (WD-932)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  test('「다음 페이지」 이동 응답을 기다리는 동안 nav 가 유지되고 포커스가 남는다 (WD-932)', async ({
    authenticatedPage: page,
  }) => {
    const capture = await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse(createAuditLogs(20), { totalElements: 29, totalPages: 2 }),
      { capture: true },
    );
    await page.goto('/admin/audit-logs');
    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    const next = nav.getByRole('button', { name: '다음 페이지' });
    await expect(next).toBeVisible();

    // 양성 대조와 붙잡힌 동안의 클릭이 같은 첫 행·대화상자를 보도록 한 번만 정의한다
    const firstRow = page.getByText('감사 로그 1', { exact: true });
    const dialog = page.getByRole('dialog');
    // 양성 대조: 잡기 전에는 같은 강제 클릭이 상세 대화상자를 연다 — 아래 「열리지 않는다」가 클릭이 닿지 않아 통과한 것이 아님을 보인다.
    // 열림은 다시 시도하는 단언으로 기다린다(고정 대기 뒤 한 번만 세면 느린 CI 에서 정상 동작이 실패한다)
    await firstRow.click({ force: true });
    await expect(dialog).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);

    // 이후 요청(2페이지)을 붙잡는다 — 붙잡힌 동안의 화면을 본다
    const held = await holdRequests(page, '/api/v1/admin/audit-logs', 'GET');
    await next.focus();
    await page.keyboard.press('Enter');
    await expect.poll(held.count).toBeGreaterThan(0);
    await expect(nav).toBeVisible();
    await expect(next).toBeFocused();
    // 이전 페이지 행이 그대로 보인다
    await expect(firstRow).toBeVisible();
    // 이동 중임을 알린다 — 표가 aria-busy 이고 이전 행은 눌러도 상세가 열리지 않는다
    const table = page.getByRole('table', { name: '감사 로그' });
    await expect(table).toHaveAttribute('aria-busy', 'true');
    // 붙잡힌 동안 이전 행으로는 상세를 열 수 없다 — 키보드(행 버튼에 포커스가 닿지 않음)와 마우스(강제 클릭) 모두.
    // 두 잠금을 각각 직접 확인한다: tbody 는 inert(포커스·클릭 차단), 표는 pointer-events: none(클릭 차단)(WD-932)
    const rowButton = page.locator('tbody button[aria-haspopup="dialog"]').first();
    expect(await isUnderInert(rowButton)).toBe(true);
    expect(await table.evaluate((el) => getComputedStyle(el).pointerEvents)).toBe('none');
    // 키보드: 행 버튼에 포커스를 주려 해도 받지 못하고 포커스는 nav 버튼에 남는다
    await rowButton.focus();
    await expect(rowButton).not.toBeFocused();
    await expect(next).toBeFocused();
    // 마우스: 강제 클릭을 해도 상세가 열리지 않는다 — 클릭은 포커스를 옮기므로 포커스 단언 뒤에 둔다
    await firstRow.click({ force: true });
    // 한 번만 세면 그려지기 전에 통과할 수 있어, 일정 시간 동안 여러 번 세어 계속 닫혀 있는지 본다
    await expectStaysClosed(page, dialog);
    // 클릭이 포커스를 옮겼으니 응답 도착 뒤 포커스 단언을 위해 다시 nav 버튼으로 돌려 둔다
    await next.focus();

    held.release();
    await expect.poll(() => capture.lastRequest()?.searchParams.get('page')).toBe('1');
    await expect(next).toBeFocused();
    await expect(next).toHaveAttribute('aria-disabled', 'true');
    // 응답이 도착하면 이동 중 표시가 사라진다
    await expect(table).not.toHaveAttribute('aria-busy', 'true');
    expect(await isUnderInert(page.locator('tbody button[aria-haspopup="dialog"]').first())).toBe(false);
  });

  test('다음 쪽이 실패해도 페이지네이션이 남고 이전 쪽으로 돌아간다 (WD-982)', async ({
    authenticatedPage: page,
  }) => {
    const API = '/api/v1/admin/audit-logs';
    await mockApi(
      page,
      'GET',
      API,
      createPageResponse(createAuditLogs(20), { totalElements: 29, totalPages: 2 }),
    );
    await page.goto('/admin/audit-logs');
    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    const firstRow = page.getByText('감사 로그 1', { exact: true });
    await expect(firstRow).toBeVisible();

    // 2쪽(page=1)만 500 — 나중에 등록한 route 가 먼저 받는다
    await failPageRequests(page, API, 1);
    // 키보드(초점+Enter)로 이동 — 실패해도 nav 가 다시 마운트되지 않아 초점이 남아야 한다 (WD-982)
    const next = nav.getByRole('button', { name: '다음 페이지' });
    await next.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(nav).toBeVisible();
    await expect(next).toBeFocused();

    await nav.getByRole('button', { name: '이전 페이지' }).click();
    await expect(firstRow).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  test('오프라인에서 다음 쪽으로 가면 오프라인 안내가 보이고 페이지네이션이 남는다 (WD-982 리뷰 후속)', async ({
    authenticatedPage: page,
  }) => {
    const API = '/api/v1/admin/audit-logs';
    // 쪽마다 다른 행을 돌려주고 받은 page 파라미터를 기록한다 — 복구 뒤 실제로 2쪽(page=1)을 받아 왔는지 가린다
    const requestedPages: string[] = [];
    await page.route(
      (url) => url.pathname === API,
      async (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const p = new URL(route.request().url()).searchParams.get('page') ?? '0';
        requestedPages.push(p);
        const logs = p === '0' ? createAuditLogs(20) : [createAuditLog({ id: 101, description: '감사 로그 2쪽 행' })];
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(logs, { page: Number(p), totalElements: 29, totalPages: 2 })),
        });
      },
    );
    await page.goto('/admin/audit-logs');
    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    await expect(page.getByText('감사 로그 1', { exact: true })).toBeVisible();

    await page.context().setOffline(true);
    await nav.getByRole('button', { name: '다음 페이지' }).click();

    await expect(
      page.getByText('인터넷 연결이 없어 감사 로그 목록을 불러오지 못하고 있습니다. 연결되면 자동으로 다시 불러옵니다.'),
    ).toBeVisible();
    await expect(nav).toBeVisible();
    // WD-982 code-review: 안내가 흐림·inert 밖에서 노출된다 — 스크린리더에 들리고 비활성처럼 보이지 않는다
    await expectOfflineNoticeExposed(page, '감사 로그');

    await page.context().setOffline(false);
    await expect(page.getByText('감사 로그 2쪽 행', { exact: true })).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: '인터넷 연결이 없어' })).toHaveCount(0);
    expect(requestedPages).toContain('1');
  });

  test('검색 결과가 0건이어도 페이지당 선택기가 보이고 바꾸면 size 가 전달된다 (WD-982 리뷰 후속)', async ({
    authenticatedPage: page,
  }) => {
    const API = '/api/v1/admin/audit-logs';
    const capture = await mockApi(page, 'GET', API, createPageResponse([], { totalElements: 0, totalPages: 0 }), {
      capture: true,
    });
    await page.goto('/admin/audit-logs');
    // 응답이 0건이어도 받았으므로 「페이지당」 줄은 남아 크기를 바꿀 수 있다
    await expect(page.getByText('페이지당')).toBeVisible();
    const sizeSelector = page.getByRole('combobox', { name: '페이지당' });
    await expect(sizeSelector).toBeVisible();
    await sizeSelector.click();
    await page.getByRole('option', { name: '50' }).click();
    await expect.poll(() => capture.lastRequest()?.searchParams.get('size')).toBe('50');
  });

  test('기록을 받기 전(첫 로딩 지연)·날짜 역순일 때 페이지당 선택기가 보이지 않는다 (WD-982)', async ({
    authenticatedPage: page,
  }) => {
    const API = '/api/v1/admin/audit-logs';
    await mockApi(page, 'GET', API, createPageResponse(createAuditLogs(20), { totalElements: 29, totalPages: 2 }));
    // 첫 로딩 지연 — 기억한 쪽 수가 0 이라 「페이지당」 줄만 홀로 그려지면 안 된다
    const held = await holdRequests(page, API, 'GET');
    await page.goto('/admin/audit-logs');
    await expect.poll(held.count).toBeGreaterThan(0);
    await expect(page.getByText('페이지당')).toHaveCount(0);
    held.release();
    await expect(page.getByText('감사 로그 1', { exact: true })).toBeVisible();
    await expect(page.getByText('페이지당')).toBeVisible();

    // 날짜 역순 — 조건이 바뀌어 기억이 비고 조회도 보류되므로 마찬가지로 숨는다
    await page.getByLabel('종료 날짜').fill('2026-09-01');
    await page.getByLabel('시작 날짜').fill('2026-09-08');
    await expect(page.getByText('날짜 범위가 올바르지 않아 조회할 수 없습니다.')).toBeVisible();
    await expect(page.getByText('페이지당')).toHaveCount(0);
  });

  test('필터(검색어)를 바꾸면 응답 전에 이전 조건의 행이 보이지 않는다 (WD-932)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/admin/audit-logs',
      createPageResponse(createAuditLogs(20), { totalElements: 29, totalPages: 2 }),
    );
    await page.goto('/admin/audit-logs');
    await expect(page.getByText('감사 로그 1', { exact: true })).toBeVisible();

    const held = await holdRequests(page, '/api/v1/admin/audit-logs', 'GET');
    await page.getByPlaceholder('설명으로 검색...').fill('다른 조건');
    await expect.poll(held.count).toBeGreaterThan(0);
    await expect(page.getByText('감사 로그 1', { exact: true })).toHaveCount(0);
    held.release();
  });
});

/**
 * 페이지 크기를 바꾸는 동안에도 크기 선택을 유지한다 (WD-1025)
 * 예전에는 크기가 바뀌면 쪽 정보 기억이 비워지고(logs 도 undefined) 페이지네이션이 통째로 사라져 크기 선택의 초점이 body 로 떨어졌다.
 */
test.describe('감사 로그 페이지네이션 — 크기 변경 중 크기 선택 유지 (WD-1025)', () => {
  const API = '/api/v1/admin/audit-logs';

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
  });

  /** 전체 45건 응답 모킹 — size 쿼리를 읽어 totalPages = ceil(45/size) 로 돌려주고 받은 size 를 기록한다 */
  async function mockTotal45(page: Page) {
    const sizes: string[] = [];
    await page.route(
      (url) => url.pathname === API,
      async (route) => {
        if (route.request().method() !== 'GET') return route.fallback();
        const size = Number(new URL(route.request().url()).searchParams.get('size') ?? '20');
        sizes.push(String(size));
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            createPageResponse(createAuditLogs(Math.min(size, 45)), {
              totalElements: 45,
              totalPages: Math.ceil(45 / size),
            }),
          ),
        });
      },
    );
    return sizes;
  }

  /** 크기 선택 트리거에 초점을 두고 키보드로 옵션 50 을 고른다 */
  async function pickSize50ByKeyboard(page: Page, trigger: Locator) {
    await trigger.focus();
    await page.keyboard.press('Enter');
    // 목록이 열려 옵션이 보인 뒤에 고른다 — 열리는 중에 초점을 주면 Enter 가 헛돌 수 있다
    const option = page.getByRole('option', { name: '50' });
    await expect(option).toBeVisible();
    await option.focus();
    await page.keyboard.press('Enter');
  }

  test('크기를 바꾸고 응답을 기다리는 동안 크기 선택이 남아 초점을 잃지 않고, 응답 뒤 쪽 수가 새 크기 기준이다 (WD-1025)', async ({
    authenticatedPage: page,
  }) => {
    await mockTotal45(page);
    await page.goto('/admin/audit-logs');
    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    const trigger = page.getByRole('combobox', { name: '페이지당' });
    await expect(page.getByText('감사 로그 1', { exact: true })).toBeVisible();
    await expect(nav.getByRole('button', { name: '3 페이지' })).toBeVisible();

    const held = await holdRequests(page, API, 'GET');
    await pickSize50ByKeyboard(page, trigger);
    await expect.poll(held.count).toBeGreaterThan(0);

    // 붙잡힌 동안: 크기 선택이 남고 초점이 크기 선택에 있다. 쪽 수는 기억한 45건을 새 크기(50)로 나눈 1쪽이라
    // 옛 크기 기준 3쪽 버튼이 남지 않는다(nav 없음)
    await expect(nav).toHaveCount(0);
    await expect(trigger).toBeVisible();
    await expect(trigger).toBeFocused();

    held.release();
    // 응답 뒤: 50건 기준 한 쪽에 45건이 다 보이고, 크기 선택은 초점을 유지한다
    await expect(page.getByText('총 45건 중 1-45')).toBeVisible();
    await expect(trigger).toBeFocused();
  });

  test('오프라인에서 크기를 바꾸면 크기 선택이 남고 연결 안내가 보이며 연결되면 새 크기로 채운다 (WD-1025)', async ({
    authenticatedPage: page,
  }) => {
    const sizes = await mockTotal45(page);
    await page.goto('/admin/audit-logs');
    const nav = page.getByRole('navigation', { name: '페이지네이션' });
    const trigger = page.getByRole('combobox', { name: '페이지당' });
    await expect(page.getByText('감사 로그 1', { exact: true })).toBeVisible();

    await page.context().setOffline(true);
    await pickSize50ByKeyboard(page, trigger);

    await expect(
      page.getByText('인터넷 연결이 없어 감사 로그 목록을 불러오지 못하고 있습니다. 연결되면 자동으로 다시 불러옵니다.'),
    ).toBeVisible();
    // 옛 크기 기준 쪽 버튼은 남지 않고(45건·50개씩 = 1쪽) 크기 선택은 남아 초점을 쥔다
    await expect(nav).toHaveCount(0);
    await expect(trigger).toBeVisible();
    await expect(trigger).toBeFocused();

    await page.context().setOffline(false);
    await expect.poll(() => sizes).toContain('50');
    await expect(page.getByText('감사 로그 45', { exact: true })).toBeVisible();
  });
});
