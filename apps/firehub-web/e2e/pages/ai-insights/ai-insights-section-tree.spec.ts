import type { Page } from '@playwright/test';

import { createTemplate, createTemplateSection } from '../../factories/ai-insight.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * AI 인사이트 섹션 트리 E2E 테스트
 * - useSectionTree 훅의 미커버 분기를 포함하여 커버리지를 향상한다.
 * - 읽기 전용(비편집) 모드 렌더링, setSections 초기화, Divider 타입,
 *   중첩 그룹 내 섹션 수정/삭제, 빌트인 템플릿 편집 불가 등을 커버한다.
 */

/** 공통 모킹 헬퍼 */
async function setupTemplate(page: Page, overrides: Parameters<typeof createTemplate>[0] = {}) {
  const template = createTemplate({ id: 30, name: '섹션 트리 테스트 템플릿', ...overrides });
  await mockApi(page, 'GET', '/api/v1/proactive/templates/30', template);
  await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
  await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
  await mockApi(page, 'PUT', '/api/v1/proactive/templates/30', { ...template });
  return template;
}

test.describe('AI 인사이트 섹션 트리 — 렌더링 및 트리 조작', () => {
  /**
   * 읽기 전용 모드에서 API로 불러온 섹션들이 트리에 표시된다.
   * useSectionTree의 setSections 초기화 + flatItems 계산 경로를 커버한다.
   */
  test('템플릿 상세 페이지에서 섹션 목록이 읽기 전용으로 표시된다', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      builtin: false,
      sections: [
        createTemplateSection({ key: 's1', type: 'text', label: '요약 섹션' }),
        createTemplateSection({ key: 's2', type: 'list', label: '목록 섹션' }),
      ],
    });

    await page.goto('/ai-insights/templates/30');
    await expect(page.getByRole('heading', { name: '섹션 트리 테스트 템플릿' })).toBeVisible({ timeout: 10000 });

    // 섹션 레이블이 표시되는지 확인 (읽기 전용 뷰)
    await expect(page.getByText('요약 섹션').first()).toBeVisible();
    await expect(page.getByText('목록 섹션').first()).toBeVisible();
  });

  /**
   * Divider 타입 섹션 추가 — addSection(type='divider') 경로
   * static: true 설정 및 label '구분선' 생성을 커버한다.
   */
  test('편집 모드에서 Divider 섹션을 추가할 수 있다', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      builtin: false,
      sections: [
        createTemplateSection({ key: 's1', type: 'text', label: '기본 섹션' }),
      ],
    });

    await page.goto('/ai-insights/templates/30');
    await expect(page.getByRole('heading', { name: '섹션 트리 테스트 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // "섹션 추가" 드롭다운 열기 → Divider 선택
    await page.getByRole('button', { name: /섹션 추가/ }).click();
    await page.getByRole('menuitem', { name: /Divider/ }).click();

    // 섹션 카운트 2개로 증가
    await expect(page.getByText('2개', { exact: true }).first()).toBeVisible();

    // "구분선" 레이블이 트리에 표시된다
    await expect(page.getByText('구분선').first()).toBeVisible();
  });

  /**
   * 빌트인 템플릿은 편집 버튼이 없거나 편집이 제한된다.
   * builtin: true 경로 렌더링 커버.
   */
  test('빌트인 템플릿에서는 편집 버튼이 표시되지 않는다', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      builtin: true,
      sections: [
        createTemplateSection({ key: 's1', type: 'text', label: '내장 섹션' }),
      ],
    });

    await page.goto('/ai-insights/templates/30');
    await expect(page.getByRole('heading', { name: '섹션 트리 테스트 템플릿' })).toBeVisible({ timeout: 10000 });

    // 빌트인 템플릿은 편집 버튼이 없다
    await expect(page.getByRole('button', { name: '편집' })).not.toBeVisible();
  });

  /**
   * 중첩 그룹 내 자식 섹션 업데이트 — updateInTree 재귀 경로 커버.
   * 그룹 자식 섹션을 선택 후 label을 변경하면 트리에 반영된다.
   */
  test('중첩 그룹 내 자식 섹션 라벨을 변경하면 트리에 즉시 반영된다', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      builtin: false,
      sections: [
        createTemplateSection({
          key: 'grp1',
          type: 'group',
          label: '분석 그룹',
          children: [
            createTemplateSection({ key: 'child1', type: 'text', label: '자식 텍스트' }),
          ],
        }),
      ],
    });

    await page.goto('/ai-insights/templates/30');
    await expect(page.getByRole('heading', { name: '섹션 트리 테스트 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 그룹과 자식이 모두 표시된다
    await expect(page.getByText('분석 그룹').first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('자식 텍스트').first()).toBeVisible();

    // 자식 섹션 클릭하여 선택
    await page.getByText('자식 텍스트', { exact: true }).first().click();

    // SectionPropertyEditor의 Label input 찾기
    const labelInput = page.locator('input[value="자식 텍스트"]').first();
    await expect(labelInput).toBeVisible();

    // 라벨 변경
    await labelInput.fill('변경된 자식');

    // 트리에 새 라벨 반영 확인
    await expect(page.getByText('변경된 자식').first()).toBeVisible();
    await expect(page.getByText('자식 텍스트', { exact: true })).toHaveCount(0);
  });

  /**
   * 중첩 그룹 내 자식 섹션 삭제 — removeFromTree 재귀 경로 커버.
   */
  test('중첩 그룹 내 자식 섹션을 삭제하면 그룹은 유지되고 자식만 제거된다', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      builtin: false,
      sections: [
        createTemplateSection({
          key: 'grp1',
          type: 'group',
          label: '분석 그룹',
          children: [
            createTemplateSection({ key: 'child1', type: 'text', label: '삭제될 자식' }),
          ],
        }),
      ],
    });

    await page.goto('/ai-insights/templates/30');
    await expect(page.getByRole('heading', { name: '섹션 트리 테스트 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 초기: 그룹 + 자식 = 2개
    await expect(page.getByText('2개', { exact: true }).first()).toBeVisible({ timeout: 10000 });

    // 자식 행 hover 후 삭제 버튼 클릭
    const childItem = page.getByText('삭제될 자식', { exact: true }).first();
    await childItem.hover();
    const childRow = childItem.locator('xpath=ancestor::div[contains(@class,"group")]').first();
    await childRow.getByRole('button').last().click({ force: true });

    // 자식 제거 후 그룹만 남아 1개
    await expect(page.getByText('1개', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('삭제될 자식', { exact: true })).toHaveCount(0);
    // 그룹 자체는 남아 있다
    await expect(page.getByText('분석 그룹').first()).toBeVisible();
  });

  /**
   * 섹션 없는 템플릿 로드 시 빈 상태가 표시된다.
   * setSections([]) + flatItems=[] 경로 커버.
   */
  test('섹션이 없는 템플릿에서 빈 상태가 표시된다', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      builtin: false,
      sections: [],
    });

    await page.goto('/ai-insights/templates/30');
    await expect(page.getByRole('heading', { name: '섹션 트리 테스트 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 섹션이 없으므로 0개
    await expect(page.getByText('0개', { exact: true }).first()).toBeVisible({ timeout: 10000 });
  });

  /**
   * Chart 타입 섹션 추가 — addSection(type='chart') 경로 커버.
   */
  test('편집 모드에서 Chart 섹션을 추가할 수 있다', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      builtin: false,
      sections: [],
    });

    await page.goto('/ai-insights/templates/30');
    await expect(page.getByRole('heading', { name: '섹션 트리 테스트 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // "섹션 추가" 드롭다운 열기 → Chart 선택
    await page.getByRole('button', { name: /섹션 추가/ }).click();
    await page.getByRole('menuitem', { name: /Chart/ }).click();

    // 섹션 카운트 1개로 증가
    await expect(page.getByText('1개', { exact: true }).first()).toBeVisible();

    // "새 chart 섹션" 라벨이 표시된다
    await expect(page.getByText('새 chart 섹션').first()).toBeVisible();

    // 자동 선택되어 key: chart_1 이 표시된다
    await expect(page.getByText(/key:\s*chart_1/)).toBeVisible();
  });

  /**
   * 여러 그룹의 독립적인 접기/펴기 상태 관리 — collapsedKeys Set 커버.
   */
  test('여러 그룹을 독립적으로 접고 펼 수 있다', async ({ authenticatedPage: page }) => {
    await setupTemplate(page, {
      builtin: false,
      sections: [
        createTemplateSection({
          key: 'grp1',
          type: 'group',
          label: '그룹 A',
          children: [
            createTemplateSection({ key: 'c1', type: 'text', label: '자식 A1' }),
          ],
        }),
        createTemplateSection({
          key: 'grp2',
          type: 'group',
          label: '그룹 B',
          children: [
            createTemplateSection({ key: 'c2', type: 'text', label: '자식 B1' }),
          ],
        }),
      ],
    });

    await page.goto('/ai-insights/templates/30');
    await expect(page.getByRole('heading', { name: '섹션 트리 테스트 템플릿' })).toBeVisible({ timeout: 10000 });
    await page.getByRole('button', { name: '편집' }).click();

    // 초기 4개 (그룹A + 자식A1 + 그룹B + 자식B1)
    await expect(page.getByText('4개', { exact: true }).first()).toBeVisible({ timeout: 10000 });

    // 그룹 A 접기
    const grpARow = page.getByText('그룹 A').first().locator('xpath=ancestor::div[contains(@class,"group")]').first();
    await grpARow.getByRole('button').first().click({ force: true });

    // 그룹 A 접힘: 자식A1 숨겨짐, 그룹B와 자식B1은 유지 → 3개
    await expect(page.getByText('3개', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('자식 A1', { exact: true })).toHaveCount(0);
    await expect(page.getByText('자식 B1').first()).toBeVisible();

    // 그룹 B도 접기
    const grpBRow = page.getByText('그룹 B').first().locator('xpath=ancestor::div[contains(@class,"group")]').first();
    await grpBRow.getByRole('button').first().click({ force: true });

    // 모두 접힘: 그룹 A + 그룹 B만 → 2개
    await expect(page.getByText('2개', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('자식 B1', { exact: true })).toHaveCount(0);

    // 그룹 A 다시 펼치기
    await grpARow.getByRole('button').first().click({ force: true });
    await expect(page.getByText('3개', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('자식 A1').first()).toBeVisible();
  });
});
