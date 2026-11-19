import { setupAdminAuth, setupRoleDetailMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/**
 * 역할 상세 섹션 제목 위계 E2E (WD-811)
 * - 카드 제목(역할 정보·데이터 열람 등급·권한 할당)은 h2 20px, 권한 그룹 제목은 h3 16px(대문자 변환 없음)로
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
    await page.goto('/admin/roles/1');

    for (const name of ['역할 정보', '데이터 열람 등급', '권한 할당']) {
      await expect(page.getByRole('heading', { level: 2, name })).toHaveCSS('font-size', '20px');
    }

    // 권한 그룹 제목(h3) — 기본 모킹 권한의 카테고리 'DATASET' 그룹을 지목한다
    const group = page.getByRole('heading', { level: 3, name: 'DATASET', exact: true });
    await expect(group).toHaveCSS('font-size', '16px');
    await expect(group).toHaveCSS('text-transform', 'none');
  });
});
