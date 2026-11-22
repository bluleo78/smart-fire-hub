import { createAuditLog, createAuditLogs } from '../../factories/admin.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { DUPLICATE_WINDOW_MS, expectSingleHeldRequest, holdRequests } from '../../fixtures/hold-api';
import { isUnderInert } from '../../fixtures/inert-a11y';

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
    const sizeSelector = page.getByRole('combobox', { name: '페이지 사이즈' });
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
    // tbody 가 inert 라 행 버튼은 포커스를 받지 못하고, 표가 pointer-events-none 이고 tbody 가 inert 라 클릭도 닿지 않는다(WD-932)
    const rowButton = page.locator('tbody button[aria-haspopup="dialog"]').first();
    expect(await isUnderInert(rowButton)).toBe(true);
    // 키보드: 행 버튼에 포커스를 주려 해도 받지 못하고 포커스는 nav 버튼에 남는다
    await rowButton.focus();
    await expect(rowButton).not.toBeFocused();
    await expect(next).toBeFocused();
    // 이동 중 nav 버튼에서 Enter 를 다시 눌러도 요청이 더 나가지 않는다 — 중복이 나갈 틈(DUPLICATE_WINDOW_MS)을 준 뒤 1회인지 센다
    await page.keyboard.press('Enter');
    await expectSingleHeldRequest(held.count, '이동 중 Enter 는 요청을 더 보내지 않아야 한다');
    // 마우스: 강제 클릭을 해도 상세가 열리지 않는다 — 클릭은 포커스를 옮기므로 포커스 단언 뒤에 둔다
    await firstRow.click({ force: true });
    // 대화상자가 그려질 틈을 준 뒤 본다 — 바로 보면 그려지기 전에 통과한다
    await page.waitForTimeout(DUPLICATE_WINDOW_MS);
    await expect(dialog).toHaveCount(0);
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
