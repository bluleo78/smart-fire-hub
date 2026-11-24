import type { Locator } from '@playwright/test';

import { createTemplate, createTemplateSection } from '../../factories/ai-insight.factory';
import { mockProactivePermissions, setupTemplateDetailMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 리포트 양식 섹션 타입 아이콘 E2E (WD-893)
 * - 섹션 타입 아이콘이 이모지가 아니라 lucide svg 로 그려지는지 검증한다.
 */

test.beforeEach(async ({ authenticatedPage: page }) => {
  await mockProactivePermissions(page);
});

// 영역 안에 이모지(Extended_Pictographic)가 하나도 없음을 단언
async function expectNoEmoji(locator: Locator) {
  const text = await locator.innerText();
  expect(text.match(/\p{Extended_Pictographic}/gu) ?? []).toEqual([]);
}

test.describe('리포트 양식 섹션 타입 아이콘', () => {
  test('미리보기 섹션 헤더가 이모지 대신 svg 아이콘을 쓴다 (WD-893)', async ({ authenticatedPage: page }) => {
    const template = createTemplate({
      id: 1,
      builtin: true,
      sections: [
        createTemplateSection({ key: 'a', type: 'text', label: '텍스트 요약' }),
        createTemplateSection({ key: 'b', type: 'cards', label: 'KPI 지표' }),
        createTemplateSection({
          key: 'g',
          type: 'group',
          label: '그룹 A',
          children: [createTemplateSection({ key: 'c', type: 'list', label: '하위 목록' })],
        }),
        createTemplateSection({ key: 'u', type: 'unknown_type' as never, label: '알 수 없는 섹션' }),
      ],
    });
    await setupTemplateDetailMocks(page, 1, template);
    await page.goto('/ai-insights/templates/1');

    const card = page.locator('[data-slot="card"]').filter({ hasText: '섹션 구조 미리보기' });
    await expect(card.getByText('텍스트 요약', { exact: true })).toBeVisible({ timeout: 10000 });
    await expectNoEmoji(card);

    const textRow = card.getByText('텍스트 요약', { exact: true }).locator('..');
    await expect(textRow.locator('svg[aria-hidden="true"]')).toHaveCount(1);
    const groupRow = card.getByText('그룹 A', { exact: true }).locator('..');
    await expect(groupRow.locator('svg[aria-hidden="true"]')).toHaveCount(1);
    const unknownLabel = card.getByText('알 수 없는 섹션', { exact: true });
    await expect(unknownLabel).toBeVisible();
    await expect(unknownLabel.locator('..').locator('svg[aria-hidden="true"]')).toHaveCount(0);
  });

  test('섹션 추가 메뉴 항목이 svg 아이콘 + 라벨 접근 이름을 갖는다 (WD-893)', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/templates', []);
    await page.goto('/ai-insights/templates/new');

    await page.getByRole('button', { name: '섹션 추가' }).click();
    for (const name of ['Text', 'Cards', 'Divider']) {
      const item = page.getByRole('menuitem', { name, exact: true });
      await expect(item).toBeVisible();
      await expect(item.locator('svg[aria-hidden="true"]')).toHaveCount(1);
    }
    await expectNoEmoji(page.getByRole('menu'));
  });

  test('편집 트리·타입 선택·타입 가이드에 이모지가 없다 (WD-893)', async ({ authenticatedPage: page }) => {
    const template = createTemplate({
      id: 1,
      builtin: false,
      sections: [
        createTemplateSection({ key: 'sum', type: 'text', label: '요약 섹션', required: false }),
        // 하위 추가 드롭다운은 group 섹션 줄에만 있어 그룹을 함께 둔다
        createTemplateSection({ key: 'grp', type: 'group', label: '묶음 섹션', children: [] }),
      ],
    });
    await setupTemplateDetailMocks(page, 1, template);
    await page.goto('/ai-insights/templates/1');
    await page.getByRole('button', { name: '편집' }).click();

    const treeRow = page.getByText('요약 섹션', { exact: true }).first().locator('..');
    // 트리 줄에는 드래그·추가·삭제 아이콘도 있어 text 타입 아이콘(lucide-type)만 센다
    await expect(treeRow.locator('svg.lucide-type[aria-hidden="true"]')).toHaveCount(1);
    await expectNoEmoji(treeRow);
    await page.getByText('요약 섹션', { exact: true }).first().click();

    const typeCombo = page.getByRole('combobox', { name: '타입', exact: true });
    await expect(typeCombo).toContainText('Text');
    await expectNoEmoji(typeCombo);

    const guide = page.getByText('타입 가이드', { exact: true }).locator('..');
    await expect(guide.locator('svg[aria-hidden="true"]')).toHaveCount(1);
    await expectNoEmoji(guide);

    // 그룹 줄의 하위 추가 드롭다운 — 트리거는 hover 때만 보여(opacity-0) force 클릭, 항목마다 svg 아이콘·이모지 없음
    const groupRow = page.getByText('묶음 섹션', { exact: true }).first().locator('..');
    await groupRow.getByRole('button', { name: '하위 섹션 추가' }).click({ force: true });
    const addChildItems = page.getByRole('menu').getByRole('menuitem');
    await expect(addChildItems.first()).toBeVisible();
    const itemCount = await addChildItems.count();
    expect(itemCount).toBeGreaterThan(0);
    for (let i = 0; i < itemCount; i++) {
      await expect(addChildItems.nth(i).locator('svg[aria-hidden="true"]')).toHaveCount(1);
    }
    await expectNoEmoji(page.getByRole('menu'));
  });

  test('JSON 탭 섹션 추가 버튼에 이모지가 없다 (WD-893)', async ({ authenticatedPage: page }) => {
    const template = createTemplate({ id: 1, builtin: false });
    await setupTemplateDetailMocks(page, 1, template);
    await page.goto('/ai-insights/templates/1');
    await page.getByRole('button', { name: '편집' }).click();
    await page.getByRole('tab', { name: 'JSON' }).click();

    const toolbar = page.getByText('섹션 추가:').locator('..');
    await expect(toolbar).toBeVisible();
    await expect(toolbar.getByRole('button', { name: 'Text' }).locator('svg')).toHaveCount(1);
    await expectNoEmoji(toolbar);
  });

  test('미리보기 alert·chart·recommendation 플레이스홀더가 lucide 아이콘을 쓴다 (WD-893)', async ({ authenticatedPage: page }) => {
    const template = createTemplate({
      id: 1,
      builtin: true,
      sections: [
        createTemplateSection({ key: 'al', type: 'alert', label: '경고' }),
        createTemplateSection({ key: 'ch', type: 'chart', label: '차트 분석' }),
        createTemplateSection({ key: 're', type: 'recommendation', label: '권고' }),
      ],
    });
    await setupTemplateDetailMocks(page, 1, template);
    await page.goto('/ai-insights/templates/1');

    const preview = page.locator('[data-slot="card"]').filter({ hasText: '섹션 구조 미리보기' });
    await expect(preview.getByText('경고', { exact: true })).toBeVisible({ timeout: 10000 });
    await expectNoEmoji(preview);
    await expect(preview.getByText('차트 영역', { exact: true })).toBeVisible();

    // 섹션 카드마다 svg 2개 = 헤더 아이콘 + 플레이스홀더 아이콘
    for (const label of ['경고', '차트 분석', '권고']) {
      const sectionCard = preview.getByTestId('section-preview-card').filter({ hasText: label });
      await expect(sectionCard.locator('svg[aria-hidden="true"]')).toHaveCount(2);
    }
  });
});
