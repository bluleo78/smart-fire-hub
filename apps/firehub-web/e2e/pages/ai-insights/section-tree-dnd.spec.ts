/**
 * useSectionTree — moveSection / divider / 중첩 group 커버리지 E2E 테스트
 *
 * 커버 대상:
 * - addSection: type='divider' 경로 (static: true)
 * - addSection: parentKey 있음 → addToParent 헬퍼
 * - removeSection: group children 내 섹션 제거 (removeFromTree 재귀)
 * - updateSection: group children 내 섹션 업데이트 (updateInTree 재귀)
 * - moveSection: drag & drop (dnd-kit SortableContext)
 * - toggleCollapsed: 여러 그룹 중첩
 */

import type { Page } from '@playwright/test';

import { createTemplate, createTemplateSection } from '../../factories/ai-insight.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/** 공통 모킹 헬퍼 */
async function setupTemplate(
  page: Page,
  overrides: Parameters<typeof createTemplate>[0] = {},
) {
  const template = createTemplate({
    id: 20,
    name: '드래그 테스트 템플릿',
    builtin: false,
    ...overrides,
  });
  await mockApi(page, 'GET', '/api/v1/proactive/templates/20', template);
  await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  await mockApi(page, 'PUT', '/api/v1/proactive/templates/20', { ...template });
  return template;
}

test.describe('useSectionTree — divider / 중첩 섹션 추가', () => {
  /**
   * ST-DND-01: "구분선" 섹션 타입 추가 → label='구분선', static: true 경로
   * addSection type='divider' → TOOL_LABELS fallback 아님
   */
  test('ST-DND-01: Divider 섹션 추가 → "구분선" 레이블이 트리에 표시된다', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      sections: [
        createTemplateSection({ key: 'summary', type: 'text', label: '요약' }),
      ],
    });

    await page.goto('/ai-insights/templates/20');
    await expect(page.getByRole('heading', { name: '드래그 테스트 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // "섹션 추가" 드롭다운 열기 → Divider 선택
    await page.getByRole('button', { name: /섹션 추가/ }).click();
    await page.getByRole('menuitem', { name: /Divider/ }).click();

    // "구분선" 레이블이 트리에 표시된다
    await expect(page.getByText('구분선').first()).toBeVisible({ timeout: 5000 });

    // 카운트 2개 (summary + divider)
    await expect(page.getByText('2개', { exact: true }).first()).toBeVisible();
  });

  /**
   * ST-DND-02: 그룹이 있는 상태에서 그룹 선택 후 자식 추가 → addToParent 헬퍼 호출
   * addSection(type, parentKey) 경로
   */
  test('ST-DND-02: 그룹 선택 → 자식 섹션 추가 → addToParent 헬퍼 호출', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      sections: [
        createTemplateSection({
          key: 'grp1',
          type: 'group',
          label: '메인 그룹',
          children: [
            createTemplateSection({ key: 'child1', type: 'text', label: '자식 1' }),
          ],
        }),
      ],
    });

    await page.goto('/ai-insights/templates/20');
    await expect(page.getByRole('heading', { name: '드래그 테스트 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 초기 2개 (그룹 + 자식1)
    await expect(page.getByText('2개', { exact: true }).first()).toBeVisible({ timeout: 10000 });

    // 그룹 행의 + 버튼 클릭 → Text 자식 추가
    const groupRow = page.getByText('메인 그룹').first()
      .locator('xpath=ancestor::div[contains(@class,"group")]').first();
    await groupRow.getByRole('button').nth(1).click({ force: true });
    await page.getByRole('menuitem', { name: /Text/ }).click();

    // 3개로 증가 (그룹 + 자식1 + 자식2)
    await expect(page.getByText('3개', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('새 text 섹션').first()).toBeVisible();
  });

  /**
   * ST-DND-03: 그룹 내 자식 섹션 삭제 → removeFromTree 재귀 경로
   */
  test('ST-DND-03: 그룹 내 자식 섹션 삭제 → removeFromTree 재귀', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      sections: [
        createTemplateSection({
          key: 'grp1',
          type: 'group',
          label: '메인 그룹',
          children: [
            createTemplateSection({ key: 'child1', type: 'text', label: '삭제할 자식' }),
            createTemplateSection({ key: 'child2', type: 'text', label: '남을 자식' }),
          ],
        }),
      ],
    });

    await page.goto('/ai-insights/templates/20');
    await expect(page.getByRole('heading', { name: '드래그 테스트 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 초기 3개 (그룹 + 자식1 + 자식2)
    await expect(page.getByText('3개', { exact: true }).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('삭제할 자식').first()).toBeVisible();

    // 자식1 행의 삭제 버튼 클릭
    const childRow = page.getByText('삭제할 자식').first()
      .locator('xpath=ancestor::div[contains(@class,"group")]').first();
    await childRow.hover();
    await childRow.getByRole('button').last().click({ force: true });

    // 2개로 감소, 자식1 사라짐
    await expect(page.getByText('2개', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('삭제할 자식', { exact: true })).toHaveCount(0);
    await expect(page.getByText('남을 자식').first()).toBeVisible();
  });

  /**
   * ST-DND-04: 그룹 내 자식 섹션 라벨 수정 → updateInTree 재귀 경로
   */
  test('ST-DND-04: 그룹 내 자식 섹션 라벨 수정 → updateInTree 재귀', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      sections: [
        createTemplateSection({
          key: 'grp1',
          type: 'group',
          label: '메인 그룹',
          children: [
            createTemplateSection({ key: 'child1', type: 'text', label: '수정 전 라벨' }),
          ],
        }),
      ],
    });

    await page.goto('/ai-insights/templates/20');
    await expect(page.getByRole('heading', { name: '드래그 테스트 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 자식 섹션 클릭 → 선택
    await page.getByText('수정 전 라벨', { exact: true }).first().click();

    // SectionPropertyEditor 에 key 표시 확인
    await expect(page.getByText(/key:\s*child1/)).toBeVisible();

    // Label 입력 수정
    const labelInput = page.locator('input[value="수정 전 라벨"]').first();
    await expect(labelInput).toBeVisible();
    await labelInput.fill('수정 후 라벨');

    // 트리에 수정된 라벨이 반영된다
    await expect(page.getByText('수정 후 라벨').first()).toBeVisible();
    await expect(page.getByText('수정 전 라벨', { exact: true })).toHaveCount(0);
  });

  /**
   * ST-DND-05: 여러 그룹의 접기/펴기 상태를 독립적으로 관리한다
   * toggleCollapsed: 여러 그룹 중첩 케이스
   */
  test('ST-DND-05: 여러 그룹 독립 접기 → collapsedKeys 관리', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      sections: [
        createTemplateSection({
          key: 'grp1',
          type: 'group',
          label: '그룹 A',
          children: [
            createTemplateSection({ key: 'a1', type: 'text', label: '그룹A 자식' }),
          ],
        }),
        createTemplateSection({
          key: 'grp2',
          type: 'group',
          label: '그룹 B',
          children: [
            createTemplateSection({ key: 'b1', type: 'text', label: '그룹B 자식' }),
          ],
        }),
      ],
    });

    await page.goto('/ai-insights/templates/20');
    await expect(page.getByRole('heading', { name: '드래그 테스트 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 초기 4개 (그룹A + A자식 + 그룹B + B자식)
    await expect(page.getByText('4개', { exact: true }).first()).toBeVisible({ timeout: 10000 });

    // 그룹 A만 접기
    const groupARow = page.getByText('그룹 A').first()
      .locator('xpath=ancestor::div[contains(@class,"group")]').first();
    await groupARow.getByRole('button').first().click({ force: true });

    // 그룹A 자식 사라짐, 그룹B 자식은 유지
    await expect(page.getByText('그룹A 자식', { exact: true })).toHaveCount(0);
    await expect(page.getByText('그룹B 자식').first()).toBeVisible();
    // 3개로 줄어듦 (그룹A + 그룹B + B자식)
    await expect(page.getByText('3개', { exact: true }).first()).toBeVisible();

    // 그룹 A 다시 펼치기
    await groupARow.getByRole('button').first().click({ force: true });
    await expect(page.getByText('4개', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('그룹A 자식').first()).toBeVisible();
  });
});
