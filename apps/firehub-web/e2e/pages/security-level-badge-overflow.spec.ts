import type { Locator, Page } from '@playwright/test';

import { createJobExecution } from '../factories/ai-insight.factory';
import { createCategories, createDatasetDetail } from '../factories/dataset.factory';
import { createLevels, createLevelSummary } from '../factories/security-level.factory';
import { setupAdminAuth, setupRoleDetailMocks } from '../fixtures/admin.fixture';
import { setupExecutionDetailMocks, setupJobDetailWithExecutionsMocks } from '../fixtures/ai-insight.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { setupDatasetDetailMocks } from '../fixtures/dataset.fixture';
import { expectNoHorizontalOverflow } from '../fixtures/long-unbroken-name';
import { setupSecurityLevelMocks } from '../fixtures/security-level.fixture';
import { expectNoTitle } from '../fixtures/table-layout';

/**
 * WD-864 — 보안 등급 배지 기본 모드가 긴 이름을 부모 폭 안에서 줄바꿈하는지 375px 에서 확인한다.
 * 상세·대화상자에서는 등급을 비교해 고르므로 자르지 않고 줄바꿈하며, title 은 두지 않는다(WD-658).
 */
test.use({ viewport: { width: 375, height: 812 } });

const LONG_NAME = '개인정보보호법 적용대상 고객민감정보 외부반출 금지 등급';
const NO_SPACE_NAME = '가'.repeat(50);

/** 자식 상자의 가로 범위가 부모 상자 안(오차 1px)에 있는지 확인한다. */
async function expectInside(child: Locator, parent: Locator) {
  const c = await child.boundingBox();
  const p = await parent.boundingBox();
  expect(c, '자식 boundingBox').not.toBeNull();
  expect(p, '부모 boundingBox').not.toBeNull();
  expect(c!.x).toBeGreaterThanOrEqual(p!.x - 1);
  expect(c!.x + c!.width).toBeLessThanOrEqual(p!.x + p!.width + 1);
}

/** 모든 배지가 부모 안에 있는지 확인한다(배지가 최소 1개 있어야 한다). */
async function expectAllInside(badges: Locator, parent: Locator) {
  const count = await badges.count();
  expect(count).toBeGreaterThan(0);
  for (let i = 0; i < count; i++) await expectInside(badges.nth(i), parent);
}

/** 등급 목록 모킹 뒤 id 2 이름만 긴 이름으로 바꿔 다시 모킹한다. */
async function setupLongLevels(page: Page, name: string) {
  await setupSecurityLevelMocks(page);
  const levels = createLevels().map((l) => (l.id === 2 ? { ...l, name } : l));
  await mockApi(page, 'GET', '/api/v1/security-levels', levels);
}

async function setupRole(page: Page, name: string) {
  await setupAdminAuth(page);
  await setupLongLevels(page, name);
  await setupRoleDetailMocks(page, 3, false);
  await mockApi(page, 'GET', '/api/v1/roles/3/clearance', {
    roleId: 3,
    securityLevelId: 2,
    userCount: 14,
    fixed: false,
  });
  await mockApi(page, 'POST', '/api/v1/roles/3/clearance/preview', {
    callerLostDatasetCount: 0,
    topLevelRoleCountAfter: 1,
    roleUserCount: 14,
  });
}

async function setupDataset(page: Page, name: string) {
  await setupAdminAuth(page);
  await setupDatasetDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await setupLongLevels(page, name);
  const base = createLevelSummary('내부');
  await mockApi(
    page,
    'GET',
    '/api/v1/datasets/1',
    createDatasetDetail({
      id: 1,
      name: '인사_평가_2026',
      securityLevel: { ...base, name },
    }),
  );
}

test.describe('보안 등급 배지 — 긴 이름 넘침 (WD-864)', () => {
  test('역할 상세 열람 등급 카드 — 긴 이름 배지가 카드 안에 있다', async ({ authenticatedPage: page }) => {
    await setupRole(page, LONG_NAME);
    await page.goto('/admin/roles/3');
    const card = page.getByTestId('role-clearance-card');
    await expect(card).toBeVisible();
    await expectAllInside(card.getByTestId('security-level-badge'), card);
    // 상세 영역은 자르지 않으므로 title 이 없어야 한다(WD-658)
    await expectNoTitle(card.getByTestId('security-level-badge').first());
    await expectNoHorizontalOverflow(page, { checkMain: false });
  });

  test('데이터셋 상세 머리·보안 탭 — 긴 이름 배지가 화면·카드 안에 있다', async ({ authenticatedPage: page }) => {
    await setupDataset(page, LONG_NAME);
    await page.goto('/data/datasets/1');
    const head = page.getByTestId('security-level-badge').first();
    await expect(head).toContainText('개인정보보호법');
    await expectInside(head, page.locator('main'));
    await expectNoTitle(head);
    await page.getByRole('tab', { name: /보안/ }).click();
    const panel = page.getByRole('tabpanel');
    await expectAllInside(panel.getByTestId('security-level-badge'), panel);
    await expectNoHorizontalOverflow(page, { checkMain: false });
  });

  test('보안 등급 변경 대화상자 — 긴 이름 배지가 대화상자 안, 페이지가 가로로 밀리지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupDataset(page, LONG_NAME);
    await page.goto('/data/datasets/1');
    await page.getByRole('button', { name: '보안 등급 변경' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expectAllInside(dialog.getByTestId('security-level-badge'), dialog);
    await expectNoTitle(dialog.getByTestId('security-level-badge').first());
    await expectNoHorizontalOverflow(page, { checkMain: false });
  });

  test('공백 없는 50자 이름도 카드 안에서 꺾인다', async ({ authenticatedPage: page }) => {
    await setupRole(page, NO_SPACE_NAME);
    await page.goto('/admin/roles/3');
    const card = page.getByTestId('role-clearance-card');
    await expect(card).toBeVisible();
    await expectAllInside(card.getByTestId('security-level-badge'), card);
    await expectNoHorizontalOverflow(page, { checkMain: false });
  });

  test('짧은 이름 배지는 한 줄 그대로', async ({ authenticatedPage: page }) => {
    await setupRole(page, LONG_NAME);
    await page.goto('/admin/roles/3');
    const card = page.getByTestId('role-clearance-card');
    const badge = card.getByTestId('security-level-badge').filter({ hasText: '공개' });
    await expect(badge).toBeVisible();
    const box = await badge.boundingBox();
    expect(box!.height).toBeLessThanOrEqual(24);
  });
});

test.describe('보안 등급 배지 — 짧은 이름은 좁은 행에서도 꺾이지 않는다 (WD-864)', () => {
  // 좁은 폭 + 줄어드는 긴 형제(「본인 열람 등급보다 높아 선택 불가」) 옆에서도 짧은 이름이 글자 단위로 꺾이면 안 된다.
  test.use({ viewport: { width: 320, height: 720 } });

  test('보안 등급 변경 대화상자 — 선택 불가 안내 옆 짧은 이름 배지는 한 줄 그대로', async ({
    authenticatedPage: page,
  }) => {
    await setupDataset(page, LONG_NAME);
    // 짧은 한글 이름·긴 영문 낱말 줄에 선택 불가 안내가 붙게 내 자격을 rank 2 로 낮춘다
    const NAMES: Record<number, string> = { 2: LONG_NAME, 3: '대외비', 4: 'Confidential' };
    const levels = createLevels().map((l) => (NAMES[l.id] ? { ...l, name: NAMES[l.id] } : l));
    await mockApi(page, 'GET', '/api/v1/security-levels', levels);
    await mockApi(page, 'GET', '/api/v1/security-levels/my-clearance', { rank: 2, levelId: 2 });
    await page.goto('/data/datasets/1');
    await page.getByRole('button', { name: '보안 등급 변경' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('본인 열람 등급보다 높아 선택 불가').first()).toBeVisible();
    for (const name of ['대외비', 'Confidential']) {
      const badge = dialog.getByTestId('security-level-badge').filter({ hasText: name });
      await expect(badge).toBeVisible();
      // 한 줄 높이(배지 기본 약 22px) — 글자 단위로 꺾이면 두세 줄로 커진다
      const box = await badge.boundingBox();
      expect(box!.height, `${name} 배지 높이`).toBeLessThanOrEqual(24);
    }
    // 긴 이름은 여전히 대화상자 안에서 줄바꿈된다
    await expectAllInside(dialog.getByTestId('security-level-badge'), dialog);
    await expectNoHorizontalOverflow(page, { checkMain: false });
  });
});

test.describe('AI 인사이트 실행 목록 — 긴 등급 배지 말줄임 (WD-864)', () => {
  test('실행 이력 표의 긴 이름 배지는 칸 안에서 말줄임되고 title 로 전체 이름을 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobDetailWithExecutionsMocks(page, 1, 1);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions', [
      {
        ...createJobExecution({ id: 1, jobId: 1, status: 'COMPLETED' }),
        securityLevel: { ...createLevelSummary('내부'), name: LONG_NAME },
      },
    ]);
    await page.goto('/ai-insights/jobs/1?tab=executions');
    const cell = page.getByRole('row').nth(1).getByRole('cell').nth(0);
    const badge = cell.getByTestId('security-level-badge');
    await expect(badge).toBeVisible();
    // 목록 표 칸 규칙(WD-658): 이름은 말줄임, 전체 이름은 title
    await expect(badge.locator('[title]')).toHaveAttribute('title', LONG_NAME);
    await expectInside(badge, cell);
    await expectNoHorizontalOverflow(page, { checkMain: false });
  });
});

test.describe('AI 인사이트 실행 상세 머리 — 긴 등급 배지 (WD-864)', () => {
  test('긴 이름 배지는 main 안에서 줄바꿈되고 「실행 #N」 제목은 한 줄이다', async ({ authenticatedPage: page }) => {
    await setupExecutionDetailMocks(page, 1, 1, 'COMPLETED');
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/1', {
      ...createJobExecution({ id: 1, jobId: 1, status: 'COMPLETED' }),
      securityLevel: { ...createLevelSummary('내부'), name: LONG_NAME },
    });
    await page.goto('/ai-insights/jobs/1/executions/1');
    const heading = page.getByRole('heading', { name: '실행 #1' });
    await expect(heading).toBeVisible();
    const badge = page.getByTestId('security-level-badge');
    await expect(badge).toContainText('개인정보보호법');
    await expectInside(badge, page.locator('main'));
    await expectNoTitle(badge);
    // 제목이 배지와 함께 줄어 꺾이지 않아야 한다(text-lg 한 줄 높이)
    const box = await heading.boundingBox();
    expect(box!.height).toBeLessThanOrEqual(30);
    await expectNoHorizontalOverflow(page, { checkMain: false });
  });
});
