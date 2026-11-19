import { createPermission } from '../../factories/admin.factory';
import { setupAdminAuth, setupRoleDetailMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/**
 * 역할 상세 섹션 제목 위계 E2E (WD-811)
 * - 카드 제목(역할 정보·데이터 열람 등급·권한 할당)은 h2 20px, 권한 그룹 제목은 h3 16px 로
 *   데이터셋 상세와 같은 h1 > h2 > h3 단계를 잇는지 검증한다.
 */
test.describe('역할 상세 섹션 제목', () => {
  test('역할 상세 카드 제목이 h2 20px, 권한 그룹 제목이 h3 16px 이다 (WD-811)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupRoleDetailMocks(page, 1);
    // 데이터 열람 등급 카드는 등급 목록·역할 자격이 있어야 그려진다
    await setupSecurityLevelMocks(page);
    await mockApi(page, 'GET', '/api/v1/roles/1/clearance', { roleId: 1, securityLevelId: 2, userCount: 1, fixed: false });
    // 실제 서버처럼 카테고리 키를 소문자로 준다(마이그레이션의 카테고리는 모두 소문자) — 나중 등록이 우선한다
    await mockApi(page, 'GET', '/api/v1/permissions', [
      createPermission({ id: 1, code: 'dataset:read', description: '데이터셋 조회', category: 'dataset' }),
    ]);
    await page.goto('/admin/roles/1');

    for (const name of ['역할 정보', '데이터 열람 등급', '권한 할당']) {
      await expect(page.getByRole('heading', { level: 2, name })).toHaveCSS('font-size', '20px');
    }

    // 권한 그룹 제목(h3) — 한국어 라벨이 없는 소문자 키는 WD-814 전까지 예전처럼 대문자로 보여야 한다
    const group = page.getByRole('heading', { level: 3 }).filter({ hasText: 'dataset' });
    await expect(group).toHaveCSS('font-size', '16px');
    await expect(group).toHaveCSS('text-transform', 'uppercase');
    expect(await group.innerText()).toBe('DATASET');
  });
});
