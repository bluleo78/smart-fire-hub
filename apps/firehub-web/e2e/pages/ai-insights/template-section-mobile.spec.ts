import type { Page } from '@playwright/test';

import { createTemplate, createTemplateSection } from '../../factories/ai-insight.factory';
import { mockProactivePermissions } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 리포트 양식 편집 — 휴대폰 폭에서 섹션 구조 카드 높이·섹션 선택 시 편집 칸 이동 (WD-376).
 * 한 열로 쌓이는 좁은 화면에서 섹션 구조 카드가 데스크톱용 고정 높이(100vh-320px)를 그대로 써 빈 공간이 생기고,
 * 섹션을 눌러도 편집 칸이 화면 밖에 열려 아무 반응이 없는 것처럼 보였다.
 */

test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

/** 섹션 하나짜리 커스텀 양식을 열고 편집 모드로 들어간다. */
async function openTemplateEditor(page: Page) {
  const template = createTemplate({
    id: 10,
    name: '휴대폰 편집 양식',
    builtin: false,
    sections: [createTemplateSection({ key: 'summary', type: 'text', label: '요약 섹션' })],
  });
  await mockApi(page, 'GET', '/api/v1/proactive/templates/10', template);
  await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

  await page.goto('/ai-insights/templates/10');
  await expect(page.getByRole('heading', { name: '휴대폰 편집 양식' })).toBeVisible({ timeout: 10000 });
  await page.getByRole('button', { name: '편집' }).click();
  await expect(page.getByText('1개', { exact: true }).first()).toBeVisible();
}

test.describe('리포트 양식 편집 — 휴대폰 폭 섹션 편집 (WD-376)', () => {
  test('390px 에서는 섹션 구조 카드가 섹션 수만큼만 차지하고, 섹션을 누르면 편집 칸으로 스크롤된다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openTemplateEditor(page);

    // 섹션 추가 버튼이 빈 카드 공간에 밀려 화면 밖으로 나가지 않는다(예전 top 855 > 844)
    await expect(page.getByRole('button', { name: /섹션 추가/ })).toBeInViewport();
    const labelInput = page.getByLabel('Label', { exact: true });
    await expect(labelInput).not.toBeInViewport();

    await page.getByText('요약 섹션', { exact: true }).first().click();

    // 편집 칸(Label·Key)이 화면 안으로 들어온다 — 같은 섹션의 편집 칸인지 값으로 확인
    await expect(labelInput).toBeInViewport();
    await expect(labelInput).toHaveValue('요약 섹션');
    await expect(page.getByText('key: summary')).toBeInViewport();
    // 머리의 고정 버튼(메뉴, 높이 약 52px)에 편집 칸 머리가 가리지 않는다
    await expect
      .poll(async () => (await page.getByText('key: summary').boundingBox())?.y ?? -1)
      .toBeGreaterThanOrEqual(52);
  });

  test('1440px 두 열에서는 카드 높이를 그대로 두고, 섹션을 눌러도 화면을 움직이지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await openTemplateEditor(page);

    // 두 열에서는 섹션 구조 카드가 화면 높이 기준 고정 높이(100vh-320px = 580px)를 유지한다
    const treeCard = page.locator('[data-slot="card"]').filter({ has: page.getByRole('button', { name: /섹션 추가/ }) });
    await expect.poll(async () => Math.round((await treeCard.boundingBox())?.height ?? 0)).toBe(580);

    const headingTop = async () => (await page.getByRole('heading', { name: '휴대폰 편집 양식' }).boundingBox())?.y;
    const before = await headingTop();
    await page.getByText('요약 섹션', { exact: true }).first().click();
    await expect(page.getByLabel('Label', { exact: true })).toHaveValue('요약 섹션');
    // 스크롤이 일어났다면 머리 제목의 위치가 바뀐다
    await page.waitForTimeout(300);
    expect(await headingTop()).toBe(before);
  });
});
