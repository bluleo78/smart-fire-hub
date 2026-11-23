/**
 * 파이프라인 캔버스 — 스크린리더 문구 한국어 (WD-918)
 *
 * xyflow 기본 안내문·이동 알림(영문, 이동 전 좌표)이 그대로 읽히던 문제를 막는다.
 * 영문 알림은 비우고 캔버스 자체 영역이 한국어 이동 알림을 낸다(WD-1046).
 * 수정 모드에는 실제 가능한 조작을, 보기 모드에는 설명만 안내한다.
 */
import type { Page } from '@playwright/test';

import { createPipelineDetail, createStep } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

const EDIT_NODE_DESC =
  '스텝 노드입니다. Enter 나 Space 로 스텝 설정을 열고, 설정을 연 스텝은 화살표 키로 옮깁니다(Shift 와 함께 누르면 크게). 노드 안 「다음 스텝 추가」·「스텝 삭제」 버튼은 Tab 키로 이동할 수 있습니다.';
const EDIT_EDGE_DESC =
  '스텝 사이 연결입니다. Enter 나 Space 로 연결을 고른 뒤 Delete 나 Backspace 로 끊고, Escape 로 선택을 풉니다.';

/** 첫 노드의 aria-describedby 가 가리키는 요소 텍스트 */
async function nodeDescription(page: Page) {
  const node = page.locator('.react-flow__node').first();
  const id = await node.getAttribute('aria-describedby');
  return (await page.locator(`[id="${id}"]`).textContent()) ?? '';
}

async function edgeDescription(page: Page) {
  return (await page.locator('[id^="react-flow__edge-desc"]').first().textContent()) ?? '';
}

/** 픽스처 파이프라인(「데이터 추출」→「데이터 변환」)을 수정 모드로 연다 */
async function gotoEditMode(page: Page) {
  await page.goto('/pipelines/1');
  await page.getByRole('button', { name: '수정' }).click();
  await expect(page.locator('.react-flow__edge')).toHaveCount(1);
}

/** 알림 영역 변경 기록을 담아 둘 window 확장 */
type LiveLogWindow = Window & { __liveMsgs: string[] };

test.describe('파이프라인 캔버스 — 스크린리더 문구 한국어 (WD-918)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
    await setupPipelineEditorMocks(page, 1);
  });

  test('수정 모드에서 노드·연결선 안내와 캔버스 이름이 한국어다', async ({ authenticatedPage: page }) => {
    await gotoEditMode(page);

    expect(await nodeDescription(page)).toBe(EDIT_NODE_DESC);
    expect(await edgeDescription(page)).toBe(EDIT_EDGE_DESC);
    await expect(page.locator('.react-flow')).toHaveAttribute('aria-label', '파이프라인 다이어그램');
  });

  test('수정 모드에서 노드를 옮기면 xyflow 영문 알림은 비어 있고 한국어 알림이 나온다', async ({ authenticatedPage: page }) => {
    await gotoEditMode(page);

    const node = page.locator('.react-flow__node').first();
    await node.focus();
    await expect(node).toBeFocused();

    // 알림 영역에 한 번이라도 글이 들어갔다 사라지는 경우까지 잡도록 변경을 모두 기록한다
    await page.evaluate(() => {
      const w = window as unknown as LiveLogWindow;
      w.__liveMsgs = [];
      const el = document.querySelector('[id^="react-flow__aria-live"]');
      new MutationObserver(() => {
        if (el?.textContent) w.__liveMsgs.push(el.textContent);
      }).observe(el!, { childList: true, characterData: true, subtree: true });
    });

    await page.keyboard.press('Enter');
    await page.keyboard.press('ArrowRight');
    await expect(page.getByTestId('canvas-move-announcement')).toContainText('옮겼습니다');
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))));

    const msgs = await page.evaluate(() => (window as unknown as LiveLogWindow).__liveMsgs);
    expect(msgs).toEqual([]);
  });

  test('노드·연결선 역할 설명이 「스텝」·「연결」이고 연결선 이름은 스텝 이름으로 읽힌다', async ({ authenticatedPage: page }) => {
    await gotoEditMode(page);

    await expect(page.locator('.react-flow__node').first()).toHaveAttribute('aria-roledescription', '스텝');
    const edge = page.locator('.react-flow__edge').first();
    await expect(edge).toHaveAttribute('aria-roledescription', '연결');
    await expect(edge).toHaveAttribute('aria-label', '「데이터 추출」에서 「데이터 변환」(으)로 연결');
  });

  test('이름 없는 새 스텝은 노드와 연결선에서 같은 「스텝 #n」으로 불린다 (WD-918, WD-1049)', async ({ authenticatedPage: page }) => {
    await gotoEditMode(page);

    // 「데이터 변환」 뒤에 이름 없는 새 스텝(3번째)을 추가한다
    await page
      .locator('.react-flow__node')
      .filter({ hasText: '데이터 변환' })
      .getByRole('button', { name: '다음 스텝 추가' })
      .click();
    await expect(page.locator('.react-flow__edge')).toHaveCount(2);

    // 그 새 스텝 뒤에 이름 없는 4번째 스텝을 또 추가한다
    await page
      .locator('.react-flow__node')
      .nth(2)
      .getByRole('button', { name: '다음 스텝 추가' })
      .click();
    await expect(page.locator('.react-flow__edge')).toHaveCount(3);

    // 노드 본문도 연결선과 같은 「스텝 #n」이고, 「(이름 없음)」은 캔버스 어디에도 없다
    const third = page.locator('.react-flow__node').nth(2);
    await expect(third.getByText('스텝 #3', { exact: true })).toBeVisible();
    // 이름 없는 노드는 번호 배지를 스크린리더에서 숨기고, 이름 있는 노드는 그대로 읽힌다
    await expect(third.getByTestId('step-number-badge')).toHaveAttribute('aria-hidden', 'true');
    await expect(page.locator('.react-flow__node').first().getByTestId('step-number-badge')).not.toHaveAttribute('aria-hidden');
    await expect(page.locator('.react-flow').getByText('(이름 없음)')).toHaveCount(0);

    const labels = await page
      .locator('.react-flow__edge')
      .evaluateAll((els) => els.map((el) => el.getAttribute('aria-label')));
    expect(labels).toContain('「데이터 변환」에서 「스텝 #3」(으)로 연결');
    expect(labels).toContain('「스텝 #3」에서 「스텝 #4」(으)로 연결');
  });

  // 빈 이름·공백뿐인 이름 모두 「스텝 #n」으로 보이고 번호 배지는 숨겨진다 (WD-1049)
  for (const [title, name] of [
    ['보기 모드에서도 이름 없는 스텝은 「스텝 #n」으로 보인다 (WD-1049)', ''],
    ['공백뿐인 스텝 이름도 「스텝 #n」으로 보이고 번호 배지는 숨겨진다 (WD-1049)', '   '],
  ] as const) {
    test(title, async ({ authenticatedPage: page }) => {
      // 상세 응답의 2번째 스텝 이름을 덮는다 — 나중에 등록한 route 가 우선한다
      await mockApi(
        page,
        'GET',
        '/api/v1/pipelines/1',
        createPipelineDetail({
          id: 1,
          steps: [createStep(), createStep({ id: 2, name, stepOrder: 1 })],
        }),
      );
      await page.goto('/pipelines/1');

      const second = page.locator('.react-flow__node').nth(1);
      await expect(second.getByText('스텝 #2', { exact: true })).toBeVisible();
      await expect(second.getByTestId('step-number-badge')).toHaveAttribute('aria-hidden', 'true');
      await expect(page.locator('.react-flow').getByText('(이름 없음)')).toHaveCount(0);
    });
  }

  test('보기 모드에서는 조작 안내 없이 설명만 읽힌다', async ({ authenticatedPage: page }) => {
    await page.goto('/pipelines/1');
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);

    expect(await nodeDescription(page)).toBe('스텝 노드입니다. Enter 나 Space 로 스텝 정보를 엽니다.');
    expect(await edgeDescription(page)).toBe('스텝 사이 연결입니다.');
    await expect(page.locator('.react-flow')).toHaveAttribute('aria-label', '파이프라인 다이어그램');
  });
});
