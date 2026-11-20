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
  test('역할 상세 카드 제목이 h2 20px, 권한 그룹 제목이 h3 16px 한국어 라벨이다 (WD-811, WD-814)', async ({
    authenticatedPage: page,
  }) => {
    await setupAdminAuth(page);
    await setupRoleDetailMocks(page, 1);
    // 데이터 열람 등급 카드는 등급 목록·역할 자격이 있어야 그려진다
    await setupSecurityLevelMocks(page);
    await mockApi(page, 'GET', '/api/v1/roles/1/clearance', { roleId: 1, securityLevelId: 2, userCount: 1, fixed: false });
    // 실제 서버처럼 카테고리 키를 소문자로 준다(마이그레이션의 카테고리는 모두 소문자) — 나중 등록이 우선한다
    // 한국어 라벨이 있는 키 3개 + 맵 밖 키(ontology2) 1개로 라벨 변환과 원시 키 폴백을 함께 본다
    await mockApi(page, 'GET', '/api/v1/permissions', [
      createPermission({ id: 1, code: 'dataset:read', description: '데이터셋 조회', category: 'dataset' }),
      createPermission({ id: 2, code: 'user:read', description: '사용자 조회', category: 'user' }),
      createPermission({ id: 3, code: 'proactive:read', description: '스마트 작업 조회', category: 'proactive' }),
      createPermission({ id: 4, code: 'ontology2:read', description: '맵 밖 조회', category: 'ontology2' }),
    ]);
    await page.goto('/admin/roles/1');

    for (const name of ['역할 정보', '데이터 열람 등급', '권한 할당']) {
      await expect(page.getByRole('heading', { level: 2, name })).toHaveCSS('font-size', '20px');
    }

    // 권한 그룹 제목(h3) — 한국어 라벨로 보이고 대문자 변환 없이 16px 이어야 한다(WD-814)
    const group = page.getByRole('heading', { level: 3, name: '데이터셋', exact: true });
    await expect(group).toHaveCSS('font-size', '16px');
    await expect(group).toHaveCSS('text-transform', 'none');

    // 권한 카드(h2 '권한 할당' 이 든 카드) 안의 h3 만 모아 순서·텍스트를 확인한다(맵 밖 키는 원시 키 그대로).
    // 페이지 다른 곳의 h3 에 영향받지 않도록 카드로 범위를 좁힌다.
    const card = page.locator('[data-slot="card"]').filter({ has: page.getByRole('heading', { level: 2, name: '권한 할당' }) });
    const titles = card.getByRole('heading', { level: 3 });
    // useInnerText: innerText 는 CSS text-transform 을 반영하므로 uppercase 가 다시 붙으면(DATASET 등) 여기서 걸린다.
    await expect(titles).toHaveText(['데이터셋', '사용자', '스마트 작업', 'ontology2'], { useInnerText: true });
  });
});
