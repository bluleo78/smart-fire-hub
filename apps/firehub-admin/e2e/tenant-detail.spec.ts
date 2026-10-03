import { createMember, createTenant } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

const ACTIVE = createTenant({ id: 1, name: '한빛소방서', slug: 'hanbit', status: 'ACTIVE', memberCount: 12, createdAt: '2026-03-04T00:21:14Z' });
const SUSPENDED = createTenant({ ...ACTIVE, status: 'SUSPENDED' });
const MEMBERS = [
  createMember({ userId: 10, username: 'kimsb', email: 'kim@example.com', role: 'OWNER', status: 'ACTIVE' }),
  createMember({ userId: 11, username: 'leejh', email: 'lee@example.com', role: 'MEMBER', status: 'ACTIVE' }),
];

test.describe('테넌트 상세', () => {
  // WD-11: 생성일시는 서버가 저장 TZ 오프셋을 붙여 준 순간을 브라우저 로컬로 그린다 — KST 로 고정해 결정적으로 단언한다.
  test.use({ timezoneId: 'Asia/Seoul' });

  test('KST 00~09시에 생성된 테넌트의 생성일시는 전날이 아니라 KST 로 보인다 (WD-11)', async ({ authenticatedPage: page }) => {
    // 운영 저장 TZ=UTC: KST 2026-03-04 00:30 생성 → 서버는 2026-03-03T15:30:00Z 로 준다.
    await mockApi(page, 'GET', '/api/platform/tenants/1', createTenant({ ...ACTIVE, createdAt: '2026-03-03T15:30:00Z' }));
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await expect(page.getByText('2026-03-04 00:30')).toBeVisible();
    await expect(page.getByText('2026-03-03 15:30')).toHaveCount(0);
  });

  test('기본 정보와 멤버 표를 그린다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await expect(page.getByRole('heading', { name: '한빛소방서' })).toBeVisible();
    await expect(page.getByText('기본 정보')).toBeVisible();
    await expect(page.getByText('hanbit')).toBeVisible();
    await expect(page.getByText('2026-03-04 09:21')).toBeVisible();
    await expect(page.getByText('멤버 (12)')).toBeVisible();
    await expect(page.getByRole('cell', { name: 'kim@example.com' })).toBeVisible();
    await expect(page.getByText('식별자', { exact: true })).toBeVisible();
    await expect(page.getByText('소유자', { exact: true })).toBeVisible();
    await expect(
      page.getByText('역할은 워크스페이스 내 표시용이며, 실제 권한은 워크스페이스 관리자가 설정합니다.'),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: '목록으로 돌아가기' })).toBeVisible();
  });

  test('정지 확인 다이얼로그가 이름·slug·인원·30분 지연을 말한다 (D-2)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    const capture = await mockApi(page, 'POST', '/api/platform/tenants/1/suspend', {}, { status: 204, capture: true });
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '테넌트 정지' }).click();

    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('테넌트 정지')).toBeVisible();
    await expect(
      dialog.getByText(
        '"한빛소방서"(hanbit)를 정지합니다. 이 워크스페이스의 멤버 12명은 새로 로그인하거나 세션을 갱신하는 시점부터 접근이 차단됩니다. 이미 발급된 세션은 최대 30분간 유효합니다. 정지는 언제든 되돌릴 수 있습니다.',
      ),
    ).toBeVisible();

    await page.getByRole('button', { name: '정지', exact: true }).click();
    await capture.waitForRequest();
    await expect(page.getByText('테넌트를 정지했습니다.')).toBeVisible();
  });

  test('정지 문구의 조사가 테넌트 이름 받침에 맞는다 (WD-6)', async ({ authenticatedPage: page }) => {
    // 받침 있는 이름(청) → "을". 괄호 뒤 조사는 괄호 앞 이름에 맞춘다.
    await mockApi(page, 'GET', '/api/platform/tenants/1', createTenant({ ...ACTIVE, name: '서울소방청', slug: 'seoul' }));
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '테넌트 정지' }).click();

    await expect(page.getByRole('alertdialog').getByText(/^"서울소방청"\(seoul\)을 정지합니다\./)).toBeVisible();
  });

  for (const [name, slug, expected] of [
    ['Acme', 'acme', '"Acme"(acme)을(를) 정지합니다.'],
    ['소방서2', 'fs2', '"소방서2"(fs2)를 정지합니다.'],
    ['소방서3', 'fs3', '"소방서3"(fs3)을 정지합니다.'],
  ]) {
    test(`정지 문구 조사: ${name} → 영문 끝은 "을(를)", 숫자 끝은 읽기 규칙 (WD-15)`, async ({ authenticatedPage: page }) => {
      await mockApi(page, 'GET', '/api/platform/tenants/1', createTenant({ ...ACTIVE, name, slug }));
      await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
      await page.goto('/tenants/1');
      await page.getByRole('button', { name: '테넌트 정지' }).click();
      await expect(page.getByRole('alertdialog').getByText(expected, { exact: false })).toBeVisible();
    });
  }

  test('식별자 표기 규칙: slug 는 mono 13px, 멤버 아이디는 일반 텍스트 (WD-7)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    const slug = page.getByText('hanbit', { exact: true });
    await expect(slug).toHaveCSS('font-family', /mono/i);
    await expect(slug).toHaveCSS('font-size', '13px');

    const table = page.getByRole('table', { name: '테넌트 멤버 목록' });
    await expect(table.getByRole('columnheader', { name: '아이디', exact: true })).toBeVisible();
    const username = table.getByRole('cell', { name: 'kimsb', exact: true });
    await expect(username).not.toHaveCSS('font-family', /mono/i);
    await expect(username).toHaveCSS('font-weight', '400');
  });

  test('멤버 역할은 한국어 배지, 알 수 없는 값은 원문 (WD-15)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', [
      createMember({ userId: 1, username: 'owner@example.com', role: 'OWNER' }),
      createMember({ userId: 2, username: 'admin@example.com', role: 'ADMIN' }),
      createMember({ userId: 3, username: 'm@example.com', role: 'MEMBER' }),
      createMember({ userId: 4, username: 'x@example.com', role: 'GUEST' }),
    ]);
    await page.goto('/tenants/1');
    const table = page.getByRole('table', { name: '테넌트 멤버 목록' });
    for (const [user, label, code] of [
      ['owner@', '소유자', 'OWNER'],
      ['admin@', '관리자', 'ADMIN'],
      ['m@', '멤버', 'MEMBER'],
      ['x@', 'GUEST', 'GUEST'],
    ]) {
      const badge = table.getByRole('row').filter({ hasText: user }).getByText(label, { exact: true });
      await expect(badge).toBeVisible();
      // 원문 코드는 title 로 남는다 — 라벨만 보고 서버 값을 추적할 수 있게.
      await expect(badge).toHaveAttribute('title', code);
    }
  });

  test('멤버 상태 열이 원시값이 아니라 한국어 배지다 (WD-8)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', [
      ...MEMBERS,
      createMember({ userId: 12, username: 'parkjs', email: 'park@example.com', role: 'MEMBER', status: 'SUSPENDED' }),
      createMember({ userId: 13, username: 'choiyh', email: 'choi@example.com', role: 'MEMBER', status: 'PENDING' }),
    ]);
    await page.goto('/tenants/1');

    const table = page.getByRole('table', { name: '테넌트 멤버 목록' });
    await expect(table.getByText('ACTIVE', { exact: true })).toHaveCount(0);
    const active = table.getByRole('row').filter({ hasText: 'kimsb' }).getByText('활성', { exact: true });
    await expect(active).toHaveAttribute('data-status', 'active');
    const suspended = table.getByRole('row').filter({ hasText: 'parkjs' }).getByText('정지', { exact: true });
    await expect(suspended).toHaveAttribute('data-status', 'warning');
    // 모르는 값은 "활성"으로 둔갑시키지 않고 원문을 unknown 톤으로 보인다
    const unknown = table.getByRole('row').filter({ hasText: 'choiyh' }).getByText('PENDING', { exact: true });
    await expect(unknown).toHaveAttribute('data-status', 'unknown');
  });

  test('정지 다이얼로그 제목이 디자인 시스템 heading-card 20px/28px 다 (#788)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '테넌트 정지' }).click();

    // 개별 className 없이 AlertDialogTitle 기본값만으로 계산되는 크기를 단언한다
    const title = page.getByRole('alertdialog').locator('[data-slot="alert-dialog-title"]');
    await expect(title).toHaveCSS('font-size', '20px');
    await expect(title).toHaveCSS('line-height', '28px');
    await expect(title).toHaveCSS('font-weight', '600');
  });

  test('정지 다이얼로그에서 취소하면 API 를 부르지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    const capture = await mockApi(page, 'POST', '/api/platform/tenants/1/suspend', {}, { status: 204, capture: true });
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '테넌트 정지' }).click();
    await page.getByRole('button', { name: '취소' }).click();
    await expect(page.getByRole('alertdialog')).not.toBeVisible();

    await page.waitForTimeout(300);
    expect(capture.lastRequest()).toBeUndefined();
  });

  test('활성화는 확인 없이 즉시 실행된다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', SUSPENDED);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    const capture = await mockApi(page, 'POST', '/api/platform/tenants/1/activate', {}, { status: 204, capture: true });
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '테넌트 활성화' }).click();
    await expect(page.getByRole('alertdialog')).not.toBeVisible();
    await capture.waitForRequest();
    await expect(page.getByText('테넌트를 활성화했습니다.')).toBeVisible();
  });

  test('정지 실패는 토스트로 알린다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await mockApi(page, 'POST', '/api/platform/tenants/1/suspend', {}, { status: 500 });
    await page.goto('/tenants/1');

    await page.getByRole('button', { name: '테넌트 정지' }).click();
    await page.getByRole('button', { name: '정지', exact: true }).click();
    await expect(page.getByText('테넌트 정지에 실패했습니다.')).toBeVisible();
  });

  test('404 는 본문 전체를 교체한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/99', {}, { status: 404 });
    await mockApi(page, 'GET', '/api/platform/tenants/99/members', [], { status: 404 });
    await page.goto('/tenants/99');

    await expect(page.getByText('테넌트를 찾을 수 없습니다.')).toBeVisible();
    await expect(page.getByRole('link', { name: '목록으로 돌아가기' })).toBeVisible();
  });

  test('멤버 조회 403 이면 멤버 카드만 배너로 바뀌고 기본 정보는 남는다', async ({ page }) => {
    await loginAs(page, ['platform:tenant:read', 'platform:tenant:suspend']);
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', {}, { status: 403 });
    await page.goto('/tenants/1');

    await expect(page.getByText('기본 정보')).toBeVisible();
    await expect(page.getByText('hanbit')).toBeVisible();
    await expect(page.getByText('멤버를 조회할 권한이 없습니다.')).toBeVisible();
  });

  test('멤버 0명이면 빈 상태를 그린다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', createTenant({ id: 1, memberCount: 0 }));
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', []);
    await page.goto('/tenants/1');
    await expect(page.getByText('멤버가 없습니다.')).toBeVisible();
  });

  test('정지 권한이 없으면 액션 버튼을 렌더하지 않는다', async ({ page }) => {
    await loginAs(page, ['platform:tenant:read', 'platform:member:read']);
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await expect(page.getByRole('heading', { name: '한빛소방서' })).toBeVisible();
    await expect(page.getByRole('button', { name: '테넌트 정지' })).toHaveCount(0);
  });

  test('멤버 조회 500 은 "멤버가 없습니다"가 아니라 실패 문구를 보여준다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', ACTIVE);
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', {}, { status: 500 });
    await page.goto('/tenants/1');

    await expect(page.getByText('기본 정보')).toBeVisible();
    await expect(page.getByText('데이터를 불러오는데 실패했습니다.')).toBeVisible();
    // "불러오지 못함"과 "진짜 0명"은 다른 화면이어야 한다 — 대조군은 위 '멤버 0명이면 빈
    // 상태를 그린다' 테스트(같은 파일)가 200+[] 로 고정한다.
    await expect(page.getByText('멤버가 없습니다.')).not.toBeVisible();
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeVisible();
  });

  test('테넌트 상세 조회 403 이면 다시 시도 버튼이 아니라 권한 배너를 보여준다(M-2)', async ({
    authenticatedPage: page,
  }) => {
    // 양성 대조군은 바로 아래 500 테스트다 — 같은 화면·같은 실패 위치에서 500 은
    // "다시 시도" 버튼을 주고, 403 은 재시도해도 소용없으므로 권한 배너로 갈려야 한다.
    const capture = await mockApi(page, 'GET', '/api/platform/tenants/1', {}, { status: 403, capture: true });
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await expect(
      page.getByRole('status').filter({ hasText: '이 작업을 수행할 권한이 없습니다.' }),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: '다시 시도' })).not.toBeVisible();

    // retry:false 검증. 전역 기본값(retry:1, retryDelay 기본식 1000*2^0=1000ms)이 여전히
    // 적용되면 403 이 ~1초 뒤 한 번 더 나간다 — 그 지연보다 넉넉히 기다려야 이 단언이 비지
    // 않는다(500ms 는 재시도 지연보다 짧아 retry:false 유무와 무관하게 통과해 공허했다).
    await page.waitForTimeout(1500);
    expect(capture.requests.length).toBe(1);
  });

  test('테넌트 상세 조회 500 은 "찾을 수 없습니다"가 아니라 실패 문구를 보여준다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/platform/tenants/1', {}, { status: 500 });
    await mockApi(page, 'GET', '/api/platform/tenants/1/members', MEMBERS);
    await page.goto('/tenants/1');

    await expect(page.getByText('데이터를 불러오는데 실패했습니다.')).toBeVisible();
    // 404("없음")와는 다른 문구여야 한다 — 대조군은 위 '404 는 본문 전체를 교체한다' 테스트
    // (같은 파일)가 고정한다.
    await expect(page.getByText('테넌트를 찾을 수 없습니다.')).not.toBeVisible();
    await expect(page.getByRole('button', { name: '다시 시도' })).toBeVisible();
  });
});
