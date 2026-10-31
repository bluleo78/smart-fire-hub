import type { Locator, Page } from '@playwright/test';

import { createTemplate, createTemplateSection } from '../factories/ai-insight.factory';
import { mockProactivePermissions, setupJobDetailMocks } from '../fixtures/ai-insight.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../fixtures/pipeline.fixture';

/**
 * 보기 ↔ 편집 폼 전환 화면의 키보드 포커스 (WD-415)
 *
 * 결함: 「수정」·「편집」을 키보드로 누르면 그 버튼이 사라지며 포커스가 body 로 떨어져, 다음 Tab 이 페이지 맨 처음부터였다.
 *       「취소」로 돌아올 때도 같았다. 이제 편집이 열리면 첫 편집 칸으로, 취소 뒤에는 다시 나타난 버튼으로 간다.
 * (데이터셋 「기본 정보」는 dataset-detail.spec.ts 에서 확인한다)
 */

/** 버튼에 키보드 포커스를 두고 Enter 로 누른다 */
async function pressWithKeyboard(page: Page, button: Locator) {
  await button.focus();
  await page.keyboard.press('Enter');
}

test.describe('편집 모드 전환 포커스 (WD-415)', () => {
  test('파이프라인 상세 「수정」은 이름 칸으로, 「취소」는 「수정」 버튼으로 포커스를 옮긴다', async ({
    authenticatedPage: page,
  }) => {
    await grantPipelinePermissions(page);
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');

    const editButton = page.getByRole('button', { name: '수정' });
    await pressWithKeyboard(page, editButton);
    await expect(page.getByPlaceholder('파이프라인 이름')).toBeFocused();

    await pressWithKeyboard(page, page.getByRole('button', { name: '취소', exact: true }));
    await expect(editButton).toBeFocused();
  });

  test('리포트 양식 상세 「편집」은 이름 칸으로, 「취소」는 「편집」 버튼으로 포커스를 옮긴다', async ({
    authenticatedPage: page,
  }) => {
    await mockProactivePermissions(page);
    const template = createTemplate({
      id: 4,
      name: '편집용 템플릿',
      builtin: false,
      sections: [createTemplateSection({ key: 'summary', type: 'text', label: '요약 섹션' })],
    });
    await mockApi(page, 'GET', '/api/v1/proactive/templates/4', template);
    await mockApi(page, 'GET', '/api/v1/proactive/templates', [template]);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });
    await page.goto('/ai-insights/templates/4');
    await expect(page.getByRole('heading', { name: '편집용 템플릿' })).toBeVisible();

    const editButton = page.getByRole('button', { name: '편집' });
    await pressWithKeyboard(page, editButton);
    await expect(page.locator('#tpl-name')).toBeFocused();

    await pressWithKeyboard(page, page.getByRole('button', { name: '취소' }));
    await expect(editButton).toBeFocused();
  });

  test('스마트 작업 상세 「편집」은 개요 탭의 첫 편집 칸으로 포커스를 옮긴다', async ({
    authenticatedPage: page,
  }) => {
    await setupJobDetailMocks(page, 1);
    await mockProactivePermissions(page);
    await page.goto('/ai-insights/jobs/1');
    await expect(page.getByRole('heading', { name: '매일 현황 리포트' })).toBeVisible();

    await pressWithKeyboard(page, page.getByRole('button', { name: '편집' }));
    // body 로 떨어지지 않고, 개요 탭 안의 편집 칸(탭 버튼이 아닌)에 포커스가 있다
    const focused = await page.evaluate(() => {
      const el = document.activeElement;
      return {
        isBody: el === document.body,
        inOverview: !!el?.closest('[role="tabpanel"]'),
        tag: el?.tagName ?? '',
      };
    });
    expect(focused.isBody).toBe(false);
    expect(focused.inOverview).toBe(true);
    expect(['INPUT', 'TEXTAREA', 'BUTTON', 'SELECT']).toContain(focused.tag);
  });
});
