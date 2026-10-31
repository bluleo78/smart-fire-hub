/**
 * 워크스페이스(테넌트) 선택 게이트 E2E.
 *
 * <p>검증 대상은 "인증됐지만 토큰에 테넌트가 없는" 상태의 처리다. 이 상태는 두 경로로 생긴다 —
 * 로그인 시 멤버십이 2개 이상이라 백엔드가 자동 선택을 하지 않은 경우, 그리고 세션 중 테넌트가
 * 정지돼 `refresh` 가 토큰을 강등한 경우. 둘 다 `refresh` 응답의 `activeTenantId === null` 로
 * 나타나므로 여기서는 그 응답을 모킹해 재현한다.
 */
import type { TokenResponse } from '../../src/types/auth';
import type { MembershipResponse, UnavailableWorkspace } from '../../src/types/tenant';
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
function tenantLessToken(
  memberships: MembershipResponse[],
  unavailableWorkspaces: UnavailableWorkspace[] = [],
): TokenResponse {
  return { ...MOCK_TOKEN_RESPONSE, activeTenantId: null, memberships, unavailableWorkspaces };
}

// WD-281: 멤버십은 있지만 정지돼 들어갈 수 없는 워크스페이스 — 사유별로 문의 상대가 다르다.
const MEMBERSHIP_SUSPENDED: UnavailableWorkspace = {
  tenantName: '감마 워크스페이스',
  reason: 'MEMBERSHIP_SUSPENDED',
};
const WORKSPACE_SUSPENDED: UnavailableWorkspace = {
  tenantName: '델타 워크스페이스',
  reason: 'WORKSPACE_SUSPENDED',
};
const MEMBERSHIP_SUSPENDED_MESSAGE =
  '워크스페이스 「감마 워크스페이스」에서 이용이 정지되었습니다. 워크스페이스 관리자에게 문의하세요.';
const WORKSPACE_SUSPENDED_MESSAGE = '워크스페이스 「델타 워크스페이스」 전체가 정지되었습니다. 운영자에게 문의하세요.';

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
    // WD-15: 역할 코드는 한국어 라벨로 보인다(OWNER→소유자, MEMBER→멤버).
    await expect(page.getByText('소유자', { exact: true })).toBeVisible();
    await expect(page.getByText('멤버', { exact: true })).toBeVisible();
    await expect(page.getByText('MEMBER', { exact: true })).toHaveCount(0);
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

  // ── WD-281: 정지된 워크스페이스가 「초대받은 적 없음」과 구분되어 안내된다 ──────────────

  // ST-09: 들어갈 수 있는 곳이 없고 정지된 곳만 있으면 초대 안내 대신 사유별 문의 상대를 알린다.
  test('ST-09: 정지된 워크스페이스만 있으면 초대 안내 대신 사유별 정지 안내를 보여준다 (WD-281)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/refresh', tenantLessToken([], [MEMBERSHIP_SUSPENDED, WORKSPACE_SUSPENDED]));
    // 선택 목록이 비면 화면이 memberships 를 한 번 직접 조회한다 — 실제 서버처럼 빈 배열을 준다.
    await mockApi(page, 'GET', '/api/v1/auth/memberships', []);

    await page.goto('/');

    await expect(page.getByRole('heading', { name: '워크스페이스 선택' })).toBeVisible();
    const notices = page.getByTestId('unavailable-workspaces');
    // 응답 순서대로, 사유별 문구(문의 상대 포함)가 그대로 렌더링된다.
    await expect(notices.locator('[data-slot="inline-banner"]')).toHaveText([
      MEMBERSHIP_SUSPENDED_MESSAGE,
      WORKSPACE_SUSPENDED_MESSAGE,
    ]);
    // 「초대받은 적 없음」 안내는 나오지 않는다 — 이것이 WD-281 의 핵심 회귀 지점이다.
    await expect(page.getByText('참여 중인 워크스페이스가 없습니다')).toHaveCount(0);
    await expect(page.getByText(/초대를 요청하세요/)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
  });

  // ST-10: 선택 가능한 곳이 있으면 목록은 그대로 두고, 정지된 곳은 목록 아래 메모로 알린다.
  test('ST-10: 선택 가능한 워크스페이스 아래에 정지된 워크스페이스를 함께 알린다 (WD-281)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/refresh', tenantLessToken([TENANT_A, TENANT_B], [WORKSPACE_SUSPENDED]));

    await page.goto('/');

    // 선택 가능한 카드는 그대로 두 개다.
    await expect(page.getByRole('button', { name: `${TENANT_A.tenantName} 워크스페이스 선택` })).toBeVisible();
    await expect(page.getByRole('button', { name: `${TENANT_B.tenantName} 워크스페이스 선택` })).toBeVisible();
    // 정지된 곳은 선택 카드가 아니라 메모로만 나온다(눌러서 403 을 받는 데드엔드를 만들지 않는다).
    await expect(page.getByRole('button', { name: `${WORKSPACE_SUSPENDED.tenantName} 워크스페이스 선택` })).toHaveCount(0);
    const note = page.getByTestId('unavailable-workspaces-note');
    await expect(note.getByText('이용할 수 없는 워크스페이스')).toBeVisible();
    await expect(note.getByRole('listitem')).toHaveText([WORKSPACE_SUSPENDED_MESSAGE]);
  });

  // ST-11: 정지된 곳이 없으면 메모 자체를 그리지 않는다(빈 메모로 화면을 어지럽히지 않게).
  test('ST-11: 정지된 워크스페이스가 없으면 안내 메모를 그리지 않는다 (WD-281)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'POST', '/api/v1/auth/refresh', tenantLessToken([TENANT_A, TENANT_B]));

    await page.goto('/');

    await expect(page.getByText(TENANT_A.tenantName)).toBeVisible();
    await expect(page.getByTestId('unavailable-workspaces-note')).toHaveCount(0);
    await expect(page.getByTestId('unavailable-workspaces')).toHaveCount(0);
  });

  // ST-12: 로그인 응답의 정지 목록이 그대로 선택 화면까지 이어진다(부팅 refresh 가 아니라 login 경로).
  test('ST-12: 로그인 응답에 정지된 멤버십만 있으면 로그인 직후 정지 안내가 보인다 (WD-281)', async ({ authMockedPage: page }) => {
    await page.goto('/login');
    // fixture 의 login 모킹을 덮어쓴다(나중에 등록한 route 가 우선).
    await mockApi(page, 'POST', '/api/v1/auth/login', tenantLessToken([], [MEMBERSHIP_SUSPENDED]));
    await mockApi(page, 'GET', '/api/v1/auth/memberships', []);

    await page.getByLabel('아이디 (이메일)').fill('test@example.com');
    await page.getByLabel('비밀번호', { exact: true }).fill('testpassword123');
    await page.getByRole('button', { name: '로그인' }).click();

    await expect(page.getByRole('heading', { name: '워크스페이스 선택' })).toBeVisible();
    await expect(page.getByTestId('unavailable-workspaces')).toHaveText(MEMBERSHIP_SUSPENDED_MESSAGE);
    await expect(page.getByText('참여 중인 워크스페이스가 없습니다')).toHaveCount(0);
  });
});
