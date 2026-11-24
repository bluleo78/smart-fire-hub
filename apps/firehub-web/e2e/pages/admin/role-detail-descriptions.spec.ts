import { createPermission, createPermissions } from '../../factories/admin.factory';
import { setupAdminAuth, setupRoleDetailMocks } from '../../fixtures/admin.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/** WD-1068 — 역할 상세의 눈에 보이는 설명을 권한 체크박스·열람 등급 라디오의 접근 설명(aria-describedby)으로 잇는다(WCAG 1.3.1). */
test.describe('역할 상세 — 설명의 접근 설명 연결', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSecurityLevelMocks(page);
    await setupRoleDetailMocks(page, 3, false);
    await mockApi(page, 'GET', '/api/v1/roles/3/clearance', {
      roleId: 3,
      securityLevelId: 2,
      userCount: 14,
      fixed: false,
    });
  });

  test('권한 체크박스가 옆의 한국어 설명을 접근 설명으로 알린다 (WD-1068)', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/roles/3');
    await expect(page.getByRole('checkbox', { name: 'DATASET_READ' })).toHaveAccessibleDescription('데이터셋 조회');
    await expect(page.getByRole('checkbox', { name: 'PIPELINE_WRITE' })).toHaveAccessibleDescription('파이프라인 수정');
  });

  test('Python 실행 권한은 설명과 읽기 범위 안내를 함께 알린다 (WD-1068)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/permissions', [
      ...createPermissions(),
      createPermission({ id: 6, code: 'pipeline:python_execute', description: 'Python 스크립트 실행', category: 'pipeline' }),
    ]);
    await page.goto('/admin/roles/3');
    await expect(page.getByRole('checkbox', { name: 'pipeline:python_execute' })).toHaveAccessibleDescription(
      /^Python 스크립트 실행 Python 스크립트는 실행 주체의 열람 등급 안의 데이터만 읽습니다/,
    );
  });

  test('설명이 없는 권한은 없는 id 를 가리키지 않는다 (WD-1068)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/permissions', [
      createPermission({ id: 1, code: 'DATASET_READ', description: '', category: 'dataset' }),
    ]);
    await page.goto('/admin/roles/3');
    const box = page.getByRole('checkbox', { name: 'DATASET_READ' });
    await expect(box).toBeVisible();
    await expect(box).not.toHaveAttribute('aria-describedby', /.+/);
  });

  test('열람 등급 라디오가 각 등급 설명을 접근 설명으로 알린다 (WD-1068)', async ({ authenticatedPage: page }) => {
    await page.goto('/admin/roles/3');
    const card = page.getByTestId('role-clearance-card');
    const radios = card.getByRole('radio');
    // 등급 목록이 로드될 때까지 기다린 뒤 개수를 센다
    await expect(radios.first()).toBeVisible();
    const count = await radios.count();
    expect(count).toBeGreaterThan(1);
    const descs: string[] = [];
    for (let i = 0; i < count; i++) {
      const radio = radios.nth(i);
      // 이 라디오를 품은 라벨(Label) 안의 설명 텍스트와 접근 설명이 같아야 한다(순서·스타일 클래스에 기대지 않는다)
      const text = (await radio.locator('xpath=ancestor::label[1]').locator('[id$="-desc"]').innerText()).trim();
      expect(text).not.toBe('');
      await expect(radio).toHaveAccessibleDescription(text);
      descs.push(text);
    }
    expect(descs[0]).not.toBe(descs[1]);
  });
});
