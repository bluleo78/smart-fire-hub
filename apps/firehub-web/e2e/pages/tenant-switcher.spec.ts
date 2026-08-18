/**
 * 사이드바 하단 워크스페이스 전환 UI E2E.
 *
 * <p>검증의 축은 둘이다 — (1) 선택지가 하나면 **표시 전용**이어야 한다(누를 수 있는데 아무 일도
 * 없는 버튼을 만들지 않는다), (2) 둘 이상이면 전환이 `tenantId` 를 정확히 실어 보내고 하드 리로드
 * 후 새 워크스페이스로 들어간다.
 */
import type { TokenResponse } from '../../src/types/auth';
import type { MembershipResponse } from '../../src/types/tenant';
import { mockApi } from '../fixtures/api-mock';
import { expect, MOCK_MEMBERSHIP, MOCK_TOKEN_RESPONSE, test } from '../fixtures/auth.fixture';

const TENANT_A: MembershipResponse = {
  tenantId: 11,
  tenantSlug: 'alpha',
  tenantName: '알파 워크스페이스',
  role: 'OWNER',
};
const TENANT_B: MembershipResponse = {
  tenantId: 22,
  tenantSlug: 'beta',
  tenantName: '베타 워크스페이스',
  role: 'MEMBER',
};

/** 활성 테넌트와 목록을 지정한 토큰 응답. */
function tokenWith(activeTenantId: number, memberships: MembershipResponse[]): TokenResponse {
  return { ...MOCK_TOKEN_RESPONSE, activeTenantId, memberships };
}

const SWITCH_TRIGGER = `워크스페이스 전환 (현재: ${TENANT_A.tenantName})`;

test.describe('워크스페이스 전환 UI', () => {
  // TS-01: 기본 픽스처는 멤버십 1개다 — 이름은 보이지만 전환 어포던스는 없어야 한다.
  test('TS-01: 참여 워크스페이스가 하나면 이름만 표시하고 전환 버튼을 만들지 않는다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await page.goto('/');

    await expect(page.getByText(MOCK_MEMBERSHIP.tenantName)).toBeVisible();
    // 드롭다운 트리거는 aria-label 로만 존재하므로, 그 이름의 버튼이 없다는 것이 표시 전용의 증거다.
    await expect(
      page.getByRole('button', { name: `워크스페이스 전환 (현재: ${MOCK_MEMBERSHIP.tenantName})` }),
    ).toHaveCount(0);
  });

  // TS-02: 둘 이상이면 드롭다운이 열리고 현재 워크스페이스가 식별된다.
  test('TS-02: 참여 워크스페이스가 둘 이상이면 드롭다운으로 목록과 현재 항목을 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/refresh', tokenWith(TENANT_A.tenantId, [TENANT_A, TENANT_B]));

    await page.goto('/');
    await page.getByRole('button', { name: SWITCH_TRIGGER }).click();

    const menu = page.getByRole('menu');
    await expect(menu.getByText(TENANT_A.tenantName)).toBeVisible();
    await expect(menu.getByText(TENANT_B.tenantName)).toBeVisible();
    // 현재 항목만 "현재 워크스페이스" 로 표시된다(sr-only 텍스트).
    await expect(menu.getByRole('menuitem').filter({ hasText: TENANT_A.tenantName })).toContainText('현재 워크스페이스');
  });

  // TS-03: 전환 → payload → 하드 리로드 후 새 워크스페이스 진입까지 전 구간.
  test('TS-03: 다른 워크스페이스를 고르면 tenantId를 전달하고 그 워크스페이스로 진입한다', async ({ authenticatedPage: page }) => {
    let switched = false;
    const capturedPayloads: unknown[] = [];

    await page.route(
      (url) => url.pathname === '/api/v1/auth/refresh',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            tokenWith(switched ? TENANT_B.tenantId : TENANT_A.tenantId, [TENANT_A, TENANT_B]),
          ),
        }),
    );
    await page.route(
      (url) => url.pathname === '/api/v1/auth/select-tenant',
      (route) => {
        capturedPayloads.push(route.request().postDataJSON());
        switched = true;
        // 실제 백엔드처럼 memberships 는 빈 배열 — 이걸로 목록을 덮으면 전환 후 칩이 빈다.
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ ...MOCK_TOKEN_RESPONSE, activeTenantId: TENANT_B.tenantId, memberships: [] }),
        });
      },
    );

    await page.goto('/');
    await page.getByRole('button', { name: SWITCH_TRIGGER }).click();
    await page.getByRole('menuitem').filter({ hasText: TENANT_B.tenantName }).click();

    await expect.poll(() => capturedPayloads.length).toBeGreaterThan(0);
    expect(capturedPayloads[0]).toEqual({ tenantId: TENANT_B.tenantId });
    // 하드 리로드 후 트리거가 새 워크스페이스를 현재로 표시한다 — 목록이 유지됐다는 증거도 된다
    // (덮였다면 일치 항목이 없어 컴포넌트가 아무것도 그리지 않는다).
    await expect(
      page.getByRole('button', { name: `워크스페이스 전환 (현재: ${TENANT_B.tenantName})` }),
    ).toBeVisible();
  });

  // TS-04: 전환 거부(403)는 401 인터셉터를 타지 않으므로 화면이 직접 알려야 한다.
  test('TS-04: 전환이 거부되면 에러를 보여주고 현재 워크스페이스에 머문다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/refresh', tokenWith(TENANT_A.tenantId, [TENANT_A, TENANT_B]));
    await mockApi(
      page,
      'POST',
      '/api/v1/auth/select-tenant',
      { status: 403, error: 'Forbidden', message: '해당 워크스페이스에 접근할 수 없습니다.' },
      { status: 403 },
    );

    await page.goto('/');
    await page.getByRole('button', { name: SWITCH_TRIGGER }).click();
    await page.getByRole('menuitem').filter({ hasText: TENANT_B.tenantName }).click();

    await expect(page.getByText('해당 워크스페이스에 접근할 수 없습니다.')).toBeVisible();
    // 목록이 열린 채 남아 바로 재시도할 수 있어야 한다 — 실패했는데 메뉴가 닫히면 사용자는
    // 무엇이 실패했는지 모른 채 처음부터 다시 열어야 한다.
    // (열린 Radix 메뉴는 바깥 요소를 aria-hidden 처리하므로 트리거를 role 로 찾을 수 없다 —
    //  그래서 "머문다" 는 트리거 존재가 아니라 메뉴 유지로 검증한다.)
    await expect(page.getByRole('menu')).toBeVisible();
    await expect(page.getByRole('menuitem').filter({ hasText: TENANT_B.tenantName })).toBeVisible();
  });

  // TS-05: 현재 워크스페이스를 다시 누르면 요청도 리로드도 없어야 한다 — 아무것도 바뀌지 않는
  // 하드 리로드는 순수 손해이고, 사용자가 실수로 자기 화면을 날리게 된다.
  test('TS-05: 현재 워크스페이스를 다시 선택하면 전환 요청을 보내지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/refresh', tokenWith(TENANT_A.tenantId, [TENANT_A, TENANT_B]));
    let selectTenantCalls = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/auth/select-tenant',
      (route) => {
        selectTenantCalls += 1;
        return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
      },
    );

    await page.goto('/');
    await page.getByRole('button', { name: SWITCH_TRIGGER }).click();
    await page.getByRole('menuitem').filter({ hasText: TENANT_A.tenantName }).click();

    // 메뉴는 닫히고(죽은 클릭이 아니다) 요청은 나가지 않는다.
    await expect(page.getByRole('menu')).toBeHidden();
    expect(selectTenantCalls).toBe(0);
  });
});
