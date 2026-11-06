/**
 * 파이프라인 편집기 머리글 — 「파이프라인 목록으로」 뒤로 가기 E2E (WD-527)
 *
 * 과거에는 `<a><button aria-label="파이프라인 목록으로">` 처럼 링크 안에 버튼이 중첩되어 있어(대화형 요소 중첩)
 * Tab 이 같은 컨트롤에 두 번(링크 → 안쪽 버튼) 멈추고, 스크린리더도 같은 컨트롤을 두 번 안내했다.
 * 이 스펙은 상세(/pipelines/:id)·새로 만들기(/pipelines/new) 두 화면에서 다음을 검증한다:
 *  1. 이름 있는 링크가 정확히 하나, 같은 이름의 버튼은 없고, 머리글 안에 `a button` 중첩이 없다.
 *  2. 키보드 Tab 정지가 하나뿐이다(Shift+Tab → Tab 으로 같은 링크, 다음 Tab 은 다른 컨트롤).
 *  3. 링크를 누르면 /pipelines 목록으로 이동한다.
 *  4. 편집 중(미저장 변경) 이 링크를 눌러도 이탈 가드(#550)가 그대로 막는다.
 */

import type { Page } from '@playwright/test';

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import {
  grantPipelinePermissions,
  setupPipelineEditorMocks,
  setupPipelineMocks,
} from '../../fixtures/pipeline.fixture';

const BACK_NAME = '파이프라인 목록으로';

/** 검증할 두 진입 화면 — 각 화면에 필요한 API 모킹을 함께 둔다 */
const SCENARIOS: { label: string; path: string; setup: (page: Page) => Promise<void> }[] = [
  {
    label: '기존 파이프라인 상세',
    path: '/pipelines/1',
    setup: (page) => setupPipelineEditorMocks(page, 1),
  },
  {
    label: '새 파이프라인',
    path: '/pipelines/new',
    // 신규 생성은 ID 없이 진입하므로 데이터셋 목록만 필요하다
    setup: (page) =>
      mockApi(page, 'GET', '/api/v1/datasets', {
        content: [], page: 0, size: 1000, totalElements: 0, totalPages: 0,
      }),
  },
];

/** 현재 포커스된 요소가 뒤로 가기 링크 자신이거나 그 안에 있는지 판정한다 — 링크는 접근 이름으로 찾는다 */
async function focusIsOnOrInsideBackLink(page: Page): Promise<{ onLink: boolean; insideLink: boolean }> {
  return page.getByRole('link', { name: BACK_NAME }).evaluate((link) => {
    const active = document.activeElement;
    return {
      onLink: active === link,
      insideLink: !!active && active !== link && link.contains(active),
    };
  });
}

test.describe('파이프라인 편집기 — 뒤로 가기 링크 (WD-527)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
    // 링크를 눌러 이동할 목록 화면 모킹
    await setupPipelineMocks(page, 2);
  });

  for (const scenario of SCENARIOS) {
    test.describe(scenario.label, () => {
      test('이름 있는 링크 하나만 있고 버튼이 중첩되지 않는다', async ({ authenticatedPage: page }) => {
        await scenario.setup(page);
        await page.goto(scenario.path);

        const header = page.locator('header').filter({ has: page.getByRole('link', { name: BACK_NAME }) });
        await expect(page.getByRole('link', { name: BACK_NAME })).toHaveCount(1);
        await expect(page.getByRole('link', { name: BACK_NAME })).toBeVisible();
        // 같은 이름의 버튼 역할이 남아 있으면 Tab 정지·스크린리더 안내가 두 번 생긴다
        await expect(page.getByRole('button', { name: BACK_NAME })).toHaveCount(0);
        // 링크 안 버튼(대화형 요소 중첩)은 머리글 어디에도 없어야 한다
        await expect(header).toHaveCount(1);
        await expect(header.locator('a button')).toHaveCount(0);
      });

      test('키보드 Tab 정지가 하나뿐이다', async ({ authenticatedPage: page }) => {
        await scenario.setup(page);
        await page.goto(scenario.path);

        const backLink = page.getByRole('link', { name: BACK_NAME });
        await expect(backLink).toBeVisible();
        await backLink.focus();
        await expect(backLink).toBeFocused();

        // 앞으로 한 칸 갔다가 다시 Tab — 같은 링크로 돌아와야 한다
        await page.keyboard.press('Shift+Tab');
        await expect(backLink).not.toBeFocused();
        await page.keyboard.press('Tab');
        await expect(backLink).toBeFocused();

        // 다음 Tab 은 링크도, 링크 안 요소도 아닌 다른 컨트롤로 가야 한다(중첩 버튼에 한 번 더 멈추지 않음)
        await page.keyboard.press('Tab');
        const focus = await focusIsOnOrInsideBackLink(page);
        expect(focus.onLink).toBe(false);
        expect(focus.insideLink).toBe(false);
        // 포커스가 어딘가의 실제 요소로 이동했는지 확인(body 로 빠지지 않음)
        const activeTag = await page.evaluate(() => document.activeElement?.tagName ?? '');
        expect(activeTag).not.toBe('BODY');
      });

      test('링크를 누르면 파이프라인 목록으로 이동한다', async ({ authenticatedPage: page }) => {
        await scenario.setup(page);
        await page.goto(scenario.path);

        await page.getByRole('link', { name: BACK_NAME }).click();

        await expect(page).toHaveURL('/pipelines');
        await expect(page.getByRole('heading', { name: '파이프라인 관리' })).toBeVisible();
        await expect(page.getByRole('cell', { name: '파이프라인 1', exact: true })).toBeVisible();
      });
    });
  }
  test('편집 중(미저장 변경) 뒤로 가기 링크를 누르면 이탈 가드가 막는다 (#550)', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');

    // 편집 모드 진입 → 스텝 추가로 dirty 상태
    await page.getByRole('button', { name: '수정' }).click();
    await page.getByRole('button', { name: '스텝 추가', exact: true }).click();
    await expect(page.getByRole('button', { name: '저장' })).toBeEnabled();

    await page.getByRole('link', { name: BACK_NAME }).click();

    // 확인 다이얼로그가 뜨고 목록으로 바로 이동하지 않는다
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await expect(page.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/pipelines/1');

    // 이탈을 확정하면 목록으로 간다
    await page.getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL('/pipelines');
  });
});
