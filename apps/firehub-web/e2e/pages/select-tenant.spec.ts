/**
 * 워크스페이스(테넌트) 선택 게이트 E2E.
 *
 * <p>검증 대상은 "인증됐지만 토큰에 테넌트가 없는" 상태의 처리다. 이 상태는 두 경로로 생긴다 —
 * 로그인 시 멤버십이 2개 이상이라 백엔드가 자동 선택을 하지 않은 경우, 그리고 세션 중 테넌트가
 * 정지돼 `refresh` 가 토큰을 강등한 경우. 둘 다 `refresh` 응답의 `activeTenantId === null` 로
 * 나타나므로 여기서는 그 응답을 모킹해 재현한다.
 */
import type { TokenResponse } from '../../src/types/auth';
import type { MembershipResponse } from '../../src/types/tenant';
import { mockApi } from '../fixtures/api-mock';
import { expect, MOCK_TOKEN_RESPONSE, test } from '../fixtures/auth.fixture';

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

/** 테넌트 미선택(또는 강등) 상태의 토큰 응답. */
function tenantLessToken(memberships: MembershipResponse[]): TokenResponse {
  return { ...MOCK_TOKEN_RESPONSE, activeTenantId: null, memberships };
}

test.describe('워크스페이스 선택 게이트', () => {
  // ST-01: 멤버십이 여럿이면 앱이 아니라 선택 화면이 뜬다. 이 게이트가 없으면 GUC 가 비어 있어
  // 모든 API 가 403 이 되고 사용자는 원인 없는 빈 화면을 본다.
  test('ST-01: 테넌트 미선택 상태로 홈에 진입하면 워크스페이스 선택 화면이 보인다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/refresh', tenantLessToken([TENANT_A, TENANT_B]));

    await page.goto('/');

    await expect(page.getByRole('heading', { name: '워크스페이스 선택' })).toBeVisible();
    // 두 워크스페이스가 모두 이름으로 표시되고, 표시용 라벨(role)도 함께 보인다.
    await expect(page.getByText(TENANT_A.tenantName)).toBeVisible();
    await expect(page.getByText(TENANT_B.tenantName)).toBeVisible();
    await expect(page.getByText('MEMBER')).toBeVisible();
  });

  // ST-02: 선택 → API payload → 재진입까지 전체 파이프라인. refresh 응답을 선택 전후로 바꿔
  // 하드 리로드 후 앱이 실제로 열리는 것까지 확인한다(상태만 갱신하면 이 검증이 불가능하다).
  test('ST-02: 워크스페이스를 선택하면 tenantId가 전달되고 앱으로 진입한다', async ({ authenticatedPage: page }) => {
    let selected = false;
    const capturedPayloads: unknown[] = [];

    // refresh 는 선택 전에는 미선택 토큰, 선택 후에는 선택된 토큰을 준다 — 하드 리로드 뒤의
    // 부팅이 이 응답 하나로 테넌트 상태를 복원하기 때문에 이 전환이 곧 검증 대상이다.
    await page.route(
      (url) => url.pathname === '/api/v1/auth/refresh',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            selected
              ? { ...MOCK_TOKEN_RESPONSE, activeTenantId: TENANT_B.tenantId, memberships: [TENANT_A, TENANT_B] }
              : tenantLessToken([TENANT_A, TENANT_B]),
          ),
        }),
    );
    await page.route(
      (url) => url.pathname === '/api/v1/auth/select-tenant',
      (route) => {
        capturedPayloads.push(route.request().postDataJSON());
        selected = true;
        // 실제 백엔드처럼 memberships 는 빈 배열로 준다 — 이걸로 기존 목록을 덮으면 안 된다.
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ ...MOCK_TOKEN_RESPONSE, activeTenantId: TENANT_B.tenantId, memberships: [] }),
        });
      },
    );

    await page.goto('/');
    await page.getByRole('button', { name: `${TENANT_B.tenantName} 워크스페이스 선택` }).click();

    // payload 검증
    await expect.poll(() => capturedPayloads.length).toBeGreaterThan(0);
    expect(capturedPayloads[0]).toEqual({ tenantId: TENANT_B.tenantId });
    // 하드 리로드 후 선택 화면이 사라지고 앱이 열린다.
    await expect(page.getByRole('heading', { name: '워크스페이스 선택' })).toBeHidden();
  });

  // ST-03: 멤버십 0개는 사용자가 스스로 풀 수 없는 상태다 — 초대 요청 안내와 로그아웃만 남긴다.
  test('ST-03: 참여 중인 워크스페이스가 없으면 초대 안내를 보여준다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/refresh', tenantLessToken([]));
    // 목록이 비어 있으면 화면이 직접 조회를 한 번 시도하므로 그쪽도 빈 배열로 응답한다.
    await mockApi(page, 'GET', '/api/v1/auth/memberships', []);

    await page.goto('/');

    await expect(page.getByText('참여 중인 워크스페이스가 없습니다')).toBeVisible();
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
  });

  // ST-04: 토큰 응답에 목록이 없어도(강등 직후 등) 화면이 스스로 목록을 조회해 복구한다.
  test('ST-04: 토큰 응답에 목록이 없으면 memberships API로 조회한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/refresh', tenantLessToken([]));
    await mockApi(page, 'GET', '/api/v1/auth/memberships', [TENANT_A, TENANT_B]);

    await page.goto('/');

    await expect(page.getByText(TENANT_A.tenantName)).toBeVisible();
    await expect(page.getByText(TENANT_B.tenantName)).toBeVisible();
  });

  // ST-05: 접근할 수 없는 테넌트는 403 으로 오고 401 재시도 인터셉터를 타지 않는다 — 화면이
  // 직접 에러를 보여줘야 하며, 선택 화면에 머물러야 한다.
  test('ST-05: 선택이 거부되면 에러를 보여주고 선택 화면에 머문다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/refresh', tenantLessToken([TENANT_A, TENANT_B]));
    await mockApi(
      page,
      'POST',
      '/api/v1/auth/select-tenant',
      { status: 403, error: 'Forbidden', message: '해당 워크스페이스에 접근할 수 없습니다.' },
      { status: 403 },
    );

    await page.goto('/');
    await page.getByRole('button', { name: `${TENANT_A.tenantName} 워크스페이스 선택` }).click();

    await expect(page.getByText('해당 워크스페이스에 접근할 수 없습니다.')).toBeVisible();
    await expect(page.getByRole('heading', { name: '워크스페이스 선택' })).toBeVisible();
  });

  // ST-07: 세션 중 강등. 백엔드는 `refresh` 때마다 멤버십/테넌트 상태를 재검증해, 정지됐으면
  // 토큰을 테넌트 미선택으로 내린다. 그 강등은 401 재시도 인터셉터 안에서 조용히 일어나므로,
  // 인터셉터가 새 토큰의 테넌트 상태를 UI 로 흘리지 않으면 화면은 이유 없는 전 API 403 루프에
  // 빠진다. 여기서는 첫 refresh 는 정상 테넌트, 두 번째부터 강등으로 응답해 그 경로를 재현한다.
  test('ST-07: 세션 중 테넌트가 강등되면 워크스페이스 선택 화면으로 돌아간다', async ({ authenticatedPage: page }) => {
    let refreshCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/auth/refresh',
      (route) => {
        refreshCount += 1;
        const demoted = refreshCount > 1;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(
            demoted
              ? tenantLessToken([TENANT_A, TENANT_B])
              : { ...MOCK_TOKEN_RESPONSE, activeTenantId: TENANT_A.tenantId, memberships: [TENANT_A, TENANT_B] },
          ),
        });
      },
    );
    // 대시보드 호출이 401 을 내면 인터셉터가 refresh 를 시도한다(= 두 번째 refresh → 강등).
    await mockApi(page, 'GET', '/api/v1/dashboard/stats', { message: 'Unauthorized' }, { status: 401 });

    await page.goto('/');

    await expect(page.getByRole('heading', { name: '워크스페이스 선택' })).toBeVisible();
    // 강등 응답이 준 목록으로 다시 선택할 수 있어야 한다 — 여기서 목록이 비면 복구가 불가능하다.
    await expect(page.getByText(TENANT_A.tenantName)).toBeVisible();
  });

  // ST-08: 강등이 "멤버십 0개" 로 오는 경우. 코드리뷰가 잡은 결함의 회귀 가드다 — 토큰 응답의
  // 빈 목록을 무시하면 낡은 목록이 남아, 이제 속하지도 않은 워크스페이스 카드를 눌러 403 만 받는
  // 데드엔드가 된다(초대 안내 화면은 정작 그 상황에서 도달 불가능해진다).
  test('ST-08: 멤버십이 전부 해제되면 낡은 목록을 남기지 않고 초대 안내로 떨어진다', async ({ authenticatedPage: page }) => {
    let refreshCount = 0;
    await page.route(
      (url) => url.pathname === '/api/v1/auth/refresh',
      (route) => {
        refreshCount += 1;
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          // 첫 부팅은 정상 테넌트 + 목록, 이후 refresh 는 강등 + **빈 목록**.
          body: JSON.stringify(
            refreshCount > 1
              ? tenantLessToken([])
              : { ...MOCK_TOKEN_RESPONSE, activeTenantId: TENANT_A.tenantId, memberships: [TENANT_A] },
          ),
        });
      },
    );
    await mockApi(page, 'GET', '/api/v1/auth/memberships', []);
    await mockApi(page, 'GET', '/api/v1/dashboard/stats', { message: 'Unauthorized' }, { status: 401 });

    await page.goto('/');

    await expect(page.getByText('참여 중인 워크스페이스가 없습니다')).toBeVisible();
    // 낡은 카드가 남아 있으면 안 된다 — 누를 수 있는 순간 403 데드엔드가 된다.
    await expect(page.getByRole('button', { name: `${TENANT_A.tenantName} 워크스페이스 선택` })).toHaveCount(0);
  });

  // ST-06: 회귀 가드 — 테넌트가 선택된 기본 픽스처에서는 선택 화면이 절대 나오지 않는다.
  // 이 단언이 없으면 게이트 조건이 뒤집혀도 141개 spec 이 한꺼번에 깨진 뒤에야 알게 된다.
  test('ST-06: 테넌트가 선택된 상태에서는 선택 화면이 나오지 않는다', async ({ authenticatedPage: page }) => {
    await page.goto('/');

    await expect(page.getByRole('heading', { name: '워크스페이스 선택' })).toBeHidden();
  });
});
