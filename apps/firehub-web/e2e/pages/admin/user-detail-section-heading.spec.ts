import { setupAdminAuth, setupUserDetailMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 사용자 상세 섹션 제목 E2E (WD-1067)
 * - 카드 제목(기본 정보·이 워크스페이스에서 활성·역할 할당·역할)이 제목 요소(h2)로 노출되어
 *   스크린리더 제목 목록으로 섹션을 찾을 수 있는지, 모양은 기존 CardTitle(16px)과 같은지 검증한다.
 */
test.describe('사용자 상세 섹션 제목 (WD-1067)', () => {
  test('관리 권한이 모두 있으면 카드 제목 셋과 제거 구역 제목이 모두 h2 16px 이다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupUserDetailMocks(page, 1);
    await page.goto('/admin/users/1');

    await expect(page.getByRole('heading', { level: 2 })).toHaveText([
      '기본 정보',
      '이 워크스페이스에서 활성',
      '역할 할당',
      '워크스페이스에서 제거',
    ]);
    for (const name of ['기본 정보', '이 워크스페이스에서 활성', '역할 할당']) {
      await expect(page.getByRole('heading', { level: 2, name })).toHaveCSS('font-size', '16px');
    }
  });

  test('user:read 만 있으면 기본 정보와 읽기 전용 역할 카드 제목이 h2 이다', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', ['user:read']);
    await setupUserDetailMocks(page, 1);
    await page.goto('/admin/users/1');

    await expect(page.getByRole('heading', { level: 2 })).toHaveText(['기본 정보', '역할']);
    await expect(page.getByRole('heading', { level: 2, name: '역할', exact: true })).toHaveCSS('font-size', '16px');
  });
});
