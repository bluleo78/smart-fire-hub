import { AUTH_FLAG_KEY } from '../src/api/client';
import { createTokenResponse } from './factories/platform.factory';
import { mockApi } from './fixtures/api-mock';
import { expect, loginAs, test } from './fixtures/auth.fixture';

test.describe('운영자 콘솔 셸', () => {
  test('상단 바에 평면 칩과 목적지 2개, 계정 메뉴가 있다', async ({ authenticatedPage: page }) => {
    await page.goto('/tenants');

    await expect(page.getByText('Smart Fire Hub')).toBeVisible();
    await expect(page.getByText('운영자 콘솔')).toBeVisible();

    const nav = page.getByRole('navigation', { name: '주요 메뉴' });
    await expect(nav.getByRole('link', { name: '테넌트' })).toBeVisible();
    await expect(nav.getByRole('link', { name: '플랫폼 설정' })).toBeVisible();
    // 목적지는 정확히 2개다 — 사이드바를 버린 이유가 여기 고정된다.
    await expect(nav.getByRole('link')).toHaveCount(2);

    // 활성 링크는 aria-current="page"
    await expect(nav.getByRole('link', { name: '테넌트' })).toHaveAttribute('aria-current', 'page');

    await page.getByRole('button', { name: '김운영' }).click();
    await expect(page.getByRole('menuitem', { name: '로그아웃' })).toBeVisible();
  });

  test('루트 경로는 /tenants 로 보낸다', async ({ authenticatedPage: page }) => {
    await page.goto('/');
    await expect(page).toHaveURL('/tenants');
  });

  test('권한이 없으면 목적지를 렌더하지 않고 안내 문구를 보여준다', async ({ page }) => {
    // platform:member:read 만 가진 운영자 — 두 목적지 모두 가려진다.
    await loginAs(page, ['platform:member:read']);
    await page.goto('/tenants');

    await expect(
      page.getByText('이 콘솔에서 접근 가능한 메뉴가 없습니다. 플랫폼 관리자에게 권한을 요청하세요.'),
    ).toBeVisible();
    // nav 랜드마크 자체는 여전히 존재해야 한다(L5) — 그래야 다음 줄의 링크 0개 단언이
    // "nav 가 통째로 사라져도 통과"하는 공허한 단언이 되지 않는다. 링크가 하나도 없어
    // 박스 크기가 0×0 이라 toBeVisible() 은 쓸 수 없으므로 DOM 존재 자체(count)를 본다.
    const nav = page.getByRole('navigation', { name: '주요 메뉴' });
    await expect(nav).toHaveCount(1);
    await expect(nav.getByRole('link')).toHaveCount(0);
  });

  test('미인증이면 /login 으로 보낸다', async ({ authMockedPage: page }) => {
    // localStorage 플래그를 심지 않았으므로 AuthProvider 가 refresh 를 시도조차 하지 않는다.
    await page.goto('/tenants');
    await expect(page).toHaveURL('/login');
  });

  test('세션 플래그는 힌트일 뿐이다 — /me 가 401 이면 플래그가 남아 있어도 셸이 렌더되지 않는다(M1)', async ({
    page,
  }) => {
    // hasAdminSession 을 손으로 심고 refresh 는 성공하지만 GET /me 가 401 인 경우다.
    // 실패 시 코드가 정상적으로 플래그를 지우기 때문에(AuthContext.tsx 의 catch), 그 제거를
    // 그대로 두면 "isAuthenticated = me!==null" 과 "isAuthenticated = !!flag" 둘 다 결국
    // false 로 수렴해 버려 이 변이를 구분하지 못한다(실측 확인함). 그래서 `removeItem` 을
    // 이 키에 한해 무력화해 "실패해도 플래그가 남는" 상황을 인위적으로 만든다 — 이 상태에서도
    // 권위가 `me` 라면 여전히 /login 으로 가야 하고, 권위가 플래그라면(변이) 셸이 그려진다.
    await page.addInitScript((key: string) => {
      localStorage.setItem(key, 'true');
      const nativeRemoveItem = Storage.prototype.removeItem;
      Storage.prototype.removeItem = function (removedKey: string) {
        if (removedKey === key) return;
        nativeRemoveItem.call(this, removedKey);
      };
    }, AUTH_FLAG_KEY);
    await mockApi(page, 'POST', '/api/platform/auth/refresh', createTokenResponse());
    await mockApi(page, 'GET', '/api/platform/auth/me', { message: 'Unauthorized' }, { status: 401 });

    await page.goto('/tenants');

    // 플래그는 여전히 'true' 다(무력화했으므로) — 그런데도 서버가 신원을 확정하지 못했으니
    // /login 으로 가야 한다는 것이 이 테스트의 핵심 단언이다.
    await expect(page.evaluate((key) => localStorage.getItem(key), AUTH_FLAG_KEY)).resolves.toBe(
      'true',
    );
    await expect(page).toHaveURL('/login');
    // 셸에서만 나오는 요소(계정 메뉴 버튼)가 한 번도 그려지지 않았는지도 함께 본다 —
    // LoginPage 스텁도 "운영자 콘솔" 문자열을 쓰므로 그 문자열만으로는 셸 렌더 여부를 가릴 수 없다.
    await expect(page.getByRole('button', { name: '김운영' })).not.toBeVisible();
  });
});
