import type { Page } from '@playwright/test';

import {
  createAiCredential,
  createAiSettings,
  createEmbeddingStatus,
  UNCONFIGURED_EMBEDDING,
} from '../../factories/admin.factory';
import {
  mockAiClassifyCredential,
  mockAiCredential,
  setupAdminAuth,
  setupEmbeddingMocks,
  setupSettingsMocks,
} from '../../fixtures/admin.fixture';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupSecurityLevelMocks } from '../../fixtures/security-level.fixture';

/**
 * 설정 탭 섹션 제목 E2E (WD-1069)
 * - 설정 페이지 각 탭의 카드 제목(모델 설정·시스템 프롬프트·분류 모델 설정·SMTP 서버 설정·
 *   임베딩 provider 설정·재임베딩·보안 등급·일반 설정)이 제목 요소(h2)로 노출되어
 *   스크린리더 제목 목록으로 섹션을 찾을 수 있는지, 모양은 기존 CardTitle(16px/600)과 같은지 검증한다.
 */
async function openTab(page: Page, tabName: string) {
  await page.goto('/admin/settings');
  await page.getByRole('tab', { name: tabName }).click();
}

test.describe('설정 탭 섹션 제목 (WD-1069)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await setupAdminAuth(page);
    await setupSettingsMocks(page, { ai: createAiSettings() });
    await mockAiCredential(
      page,
      createAiCredential({ agentType: 'cli-api', configured: true, secretFieldNames: ['apiKey'] }),
    );
    await mockAiClassifyCredential(page);
  });

  test('AI 에이전트 탭 — 모델 설정·시스템 프롬프트가 h2 이고 입력란 이름은 그대로', async ({
    authenticatedPage: page,
  }) => {
    await openTab(page, 'AI 에이전트');

    await expect(page.getByRole('heading', { level: 2 })).toHaveCount(2);
    const model = page.getByRole('heading', { name: '모델 설정', exact: true });
    await expect(model).toBeVisible();
    await expect(page.getByRole('heading', { name: /^시스템 프롬프트/ })).toBeVisible();
    await expect(page.getByRole('textbox', { name: '시스템 프롬프트', exact: true })).toBeVisible();
    await expect(model).toHaveCSS('font-size', '16px');
    await expect(model).toHaveCSS('font-weight', '600');
    await expect(model).toHaveAttribute('data-slot', 'card-title');
  });

  test('임베딩 탭 — 임베딩 provider 설정·재임베딩이 따로 h2', async ({ authenticatedPage: page }) => {
    await setupEmbeddingMocks(page, {
      config: UNCONFIGURED_EMBEDDING,
      status: createEmbeddingStatus({ configured: false, model: null, dimension: null }),
    });
    await openTab(page, '임베딩');
    const headings = page.getByRole('heading', { level: 2 });
    await expect(headings).toHaveText(['임베딩 provider 설정', '재임베딩']);
    await expect(headings.first()).toHaveCSS('font-size', '16px');
  });

  // 카드 제목이 하나뿐인 탭 — 열 탭·제목·탭 전용 모킹만 다르고 단언은 같아 표로 돈다.
  const singleHeadingTabs: { tab: string; name: string; setup?: (page: Page) => Promise<void> }[] = [
    { tab: 'AI 분류', name: '분류 모델 설정' },
    { tab: '이메일', name: 'SMTP 서버 설정' },
    { tab: '일반', name: '일반 설정' },
    // 「등급 추가」 버튼이 같은 머리줄에 있어도 제목 이름에 섞이지 않는지 exact 로 본다.
    { tab: '데이터 보안', name: '보안 등급', setup: setupSecurityLevelMocks },
  ];
  for (const { tab, name, setup } of singleHeadingTabs) {
    test(`${tab} 탭 — ${name} h2`, async ({ authenticatedPage: page }) => {
      if (setup) await setup(page);
      await openTab(page, tab);
      const h = page.getByRole('heading', { level: 2, name, exact: true });
      await expect(h).toBeVisible();
      await expect(h).toHaveCSS('font-size', '16px');
    });
  }
});
