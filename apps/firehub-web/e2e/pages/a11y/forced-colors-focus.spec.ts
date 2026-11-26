import { createRequire } from 'node:module';

import type { Locator, Page } from '@playwright/test';

import { createJob } from '../../factories/ai-insight.factory';
import { createRole, createUserDetail } from '../../factories/auth.factory';
import {
  createDatasetDetail,
  createDatasets,
} from '../../factories/dataset.factory';
import { createPropertyReviewItem } from '../../factories/reviewItem.factory';
import { setupAdminAuth } from '../../fixtures/admin.fixture';
import {
  mockProactivePermissions,
  setupJobListMocks,
} from '../../fixtures/ai-insight.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import {
  DATASET_WRITE_PERMISSIONS,
  grantDatasetPermissions,
  mockDatasetReferences,
  setupDatasetDetailMocks,
  setupDatasetMocks,
} from '../../fixtures/dataset.fixture';
import { holdApi } from '../../fixtures/hold-api';
import { readFocusStyle, tabUntilFocused } from '../../fixtures/keyboard-focus';
import {
  grantPipelinePermissions,
  setupPipelineEditorMocks,
} from '../../fixtures/pipeline.fixture';

/**
 * 고대비(forced-colors) 모드 전역 키보드 초점 표시 (WD-949, WD-944 후속)
 * - 초점 표시를 box-shadow ring 으로만 그리고 outline-none 을 둔 부품(shadcn 입력·표 정렬 헤더·UserNav 등)은
 *   고대비 모드에서 box-shadow 가 그려지지 않아 키보드 초점이 보이지 않았다.
 * - index.css 의 레이어 밖 forced-colors 규칙이 모든 :focus-visible 에 시스템 색 outline 을 그린다.
 *   보통 모드는 그대로(outline 없음 + ring).
 */

/**
 * 데이터셋 목록 화면 준비 — 데스크톱 뷰포트로 목록 API 를 모킹하고 /data/datasets 로 이동한다.
 * 여러 시험이 같은 준비를 되풀이하던 것을 한곳에 모은다.
 */
async function gotoDatasetList(page: Page) {
  await page.setViewportSize({ width: 1280, height: 720 });
  await setupDatasetMocks(page);
  await mockApi(
    page,
    'GET',
    '/api/v1/datasets',
    createPageResponse(createDatasets(3)),
  );
  await page.goto('/data/datasets');
  await expect(page.getByPlaceholder('데이터셋 검색...')).toBeVisible();
}

/**
 * 검수 인박스 화면 준비(항목 없음) — 권한·빈 목록을 모킹하고 /knowledge-graph/review 로 이동한다.
 * 항목이 있어야 하는 시험은 모킹이 달라 이 헬퍼를 쓰지 않는다.
 */
async function gotoReviewInbox(page: Page) {
  await mockApi(page, 'GET', '/api/v1/auth/me/permissions', [
    'dataset:read',
    'dataset:write',
  ]);
  await mockApi(page, 'GET', '/api/v1/graphrag/review-items', []);
  await page.goto('/knowledge-graph/review');
}

/**
 * 고정 요소를 페이지에 붙이고(선택: 규칙 포함) 키보드 직후 스크립트 초점을 줘 계산 스타일을 읽는다.
 * React·Radix 초점 처리에 기대지 않고 전역 CSS 규칙만 시험하기 위한 헬퍼 — Tab 한 번 뒤 focus() 라 :focus-visible 이 된다.
 * @param html - body 끝에 붙일 마크업
 * @param selectors - 차례로 초점을 줄 요소 선택자(결과도 같은 순서)
 * @param css - 함께 붙일 레이어 밖 스타일(선택)
 */
async function injectAndFocus(
  page: Page,
  html: string,
  selectors: string[],
  css?: string,
) {
  await page.evaluate(
    ({ html, css }) => {
      if (css) {
        const st = document.createElement('style');
        st.textContent = css;
        document.head.appendChild(st);
      }
      document.body.insertAdjacentHTML('beforeend', html);
    },
    { html, css },
  );
  await page.keyboard.press('Tab');
  const styles = [];
  for (const selector of selectors) {
    const el = page.locator(selector);
    await el.evaluate((node) => node.focus());
    styles.push(await readFocusStyle(el));
  }
  return styles;
}

/** 대표 요소 4종 — 키보드(Tab)로 초점을 옮기는 방법을 함께 둔다 */
const targets: { name: string; focus: (page: Page) => Promise<Locator> }[] = [
  {
    name: '비밀번호 입력',
    focus: async (page) => {
      await page.goto('/login');
      const password = page.getByLabel('비밀번호', { exact: true });
      await expect(password).toBeVisible();
      await page.getByLabel('아이디 (이메일)').click();
      await tabUntilFocused(page, password);
      return password;
    },
  },
  {
    name: '여러 줄 입력(textarea)',
    focus: async (page) => {
      await grantDatasetPermissions(page);
      await setupDatasetDetailMocks(page, 1);
      await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
      await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
      await mockApi(
        page,
        'GET',
        '/api/v1/datasets/1',
        createDatasetDetail({ id: 1 }),
      );
      await page.goto('/data/datasets/1');
      await expect(
        page.getByRole('heading', { name: '테스트 데이터셋' }),
      ).toBeVisible();
      await page.getByRole('button', { name: '복제' }).click();
      const dialog = page.getByRole('dialog');
      await expect(
        dialog.getByRole('heading', { name: '데이터셋 복제' }),
      ).toBeVisible();
      const textarea = dialog.getByLabel('설명');
      await dialog.getByLabel('데이터셋 이름 *').click();
      await tabUntilFocused(page, textarea);
      return textarea;
    },
  },
  {
    name: '표 정렬 헤더 버튼',
    focus: async (page) => {
      await gotoDatasetList(page);
      const sortButton = page
        .getByRole('columnheader', { name: /이름/ })
        .getByRole('button');
      await expect(sortButton).toBeVisible();
      await page.getByPlaceholder('데이터셋 검색...').click();
      await tabUntilFocused(page, sortButton);
      return sortButton;
    },
  },
  {
    name: '사용자 메뉴(UserNav) 트리거',
    focus: async (page) => {
      await gotoDatasetList(page);
      const trigger = page
        .getByRole('button', { name: /테스트 사용자/ })
        .first();
      await expect(trigger).toBeVisible();
      await page.getByPlaceholder('데이터셋 검색...').click();
      await tabUntilFocused(page, trigger, 150);
      return trigger;
    },
  },
];

/** 비밀번호 입력은 로그아웃 상태 화면이라 authMockedPage, 나머지는 로그인된 authenticatedPage 를 쓴다 */
const [loginTarget, ...appTargets] = targets;

/** 대상에 Tab 초점을 준 뒤 스타일을 읽고, Tab 초점이라 :focus-visible 인지 먼저 단언한다 */
async function focusAndRead(page: Page, target: (typeof targets)[number]) {
  const style = await readFocusStyle(await target.focus(page));

  expect(style.focusVisible, 'Tab 초점이라 :focus-visible 이어야 한다').toBe(
    true,
  );
  return style;
}

/** 고대비 시험 본문 — 대상에 Tab 초점을 준 뒤 outline 을 단언한다 */
async function expectForcedColorsOutline(
  page: Page,
  target: (typeof targets)[number],
) {
  await page.emulateMedia({ forcedColors: 'active' });
  const style = await focusAndRead(page, target);
  // outline-none 이 붙은 요소에서도 레이어 밖 규칙이 이겨 solid 가 되어야 한다
  expect(
    style.outlineStyle,
    '고대비에서 outline-style 이 none 이면 초점이 안 보인다',
  ).toBe('solid');
  expect(style.outlineWidth).toBe('2px');
}

/** 보통 모드 시험 본문 — outline 없이 ring(box-shadow)만 있어야 한다 */
async function expectNormalRing(page: Page, target: (typeof targets)[number]) {
  const style = await focusAndRead(page, target);
  expect(
    style.outlineStyle,
    '보통 모드에서 outline 이 그려지면 ring 과 이중 테두리가 된다',
  ).toBe('none');
  expect(style.boxShadow, '보통 모드에서는 ring 이 있어야 한다').not.toBe(
    'none',
  );
}

test.describe('고대비 모드 전역 초점 표시 (WD-949)', () => {
  test('고대비 모드: 안쪽 offset(offset-[-2px])을 쓰는 대화 전환 목록 항목은 outline 이 그려지고 offset 이 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await page.setViewportSize({ width: 1280, height: 720 });
    await mockApi(page, 'GET', '/api/v1/ai/sessions', [
      {
        id: 40,
        sessionId: 'session-949-a',
        title: '첫 번째 대화',
        createdAt: '2026-07-30T00:00:00Z',
        updatedAt: '2026-07-30T01:00:00Z',
      },
    ]);
    await page.goto('/', { waitUntil: 'commit' });
    // AI 상태 칩을 눌러 사이드 패널을 연다 — 헤더의 SessionSwitcher 가 나타난다
    await page.getByRole('button', { name: /AI 상태/ }).click();
    await page.getByRole('button', { name: /대화 선택|첫 번째 대화/ }).first().click();
    // 줄의 삭제 버튼 이름에도 제목이 들어 있어 목록 안에서 정확히 일치하는 제목 버튼만 고른다
    const item = page
      .getByRole('list', { name: '최근 대화' })
      .getByRole('button', { name: '첫 번째 대화', exact: true });
    await expect(item).toBeVisible();
    // 목록 항목(focus-visible:outline-offset-[-2px])에 키보드 초점을 준다
    await tabUntilFocused(page, item);
    const style = await readFocusStyle(item);

    expect(style.focusVisible, 'Tab 초점이라 :focus-visible 이어야 한다').toBe(
      true,
    );
    expect(style.outlineStyle, '고대비에서 outline 이 보여야 한다').toBe(
      'solid',
    );
    expect(style.outlineWidth).toBe('2px');
    expect(
      style.outlineOffset,
      '전역 규칙이 안쪽 offset 을 덮어쓰면 overflow 조상에 잘린다',
    ).toBe('-2px');
  });

  test('고대비 모드: tabindex=-1 초점 복귀 지점(근거 영역)에는 outline 이 그려진다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', [
      'dataset:read',
      'dataset:write',
    ]);
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', [
      createPropertyReviewItem(),
    ]);
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items/2/evidence', [
      { chunkId: 7, content: '약 수천만원대의 재산피해가 발생했다.' },
    ]);
    await page.goto('/knowledge-graph/review');
    await page.getByRole('button', { name: '원문 근거 보기' }).click();
    const live = page.getByTestId('review-evidence-live-2');
    await expect(live).toBeVisible();
    await expect(live).toHaveAttribute('tabindex', '-1');
    // 재시도 뒤 초점 복귀와 같은 경로 — 키보드 직후 스크립트로 초점을 주면 :focus-visible 이 된다
    await page.keyboard.press('Tab');
    await live.evaluate((node) => node.focus());
    const style = await readFocusStyle(live);

    expect(style.focusVisible).toBe(true);
    expect(
      style.outlineStyle,
      'tabindex=-1 복귀 지점도 고대비에서 초점이 보여야 한다',
    ).toBe('solid');
    expect(
      style.outlineOffset,
      '바깥 outline 은 overflow 조상에 잘려 안 보인다',
    ).toBe('-2px');
  });

  // WD-1020: 보통 모드(forcedColors 없음)에서도 인박스 표 초점 outline 이 안쪽으로 그려져야 한다.
  // 기본 바깥 outline(auto 1px, offset 0)은 표 래퍼(overflow:auto)에 잘려 복귀 지점이 안 보였다.
  test('보통 모드: 검수 인박스 표(tabindex=-1)는 안쪽 outline 이 그려져 컨테이너에 잘리지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await gotoReviewInbox(page);
    const table = page.getByTestId('review-inbox-table');
    await expect(table).toBeVisible();
    // 페이지가 직접 주는 속성 — 시험이 대신 주면 페이지 회귀를 못 잡는다
    await expect(table).toHaveAttribute('tabindex', '-1');
    // 키보드 직후 스크립트 초점 = 「다시 시도」 뒤 복귀 경로
    await page.keyboard.press('Tab');
    await table.evaluate((node) => {
      node.focus();
    });
    const style = await readFocusStyle(table);

    expect(style.focusVisible).toBe(true);
    expect(style.outlineStyle).not.toBe('none');
    expect(style.outlineStyle).not.toBe('auto');
    expect(style.outlineWidth).toBe('2px');
    expect(style.outlineOffset, '바깥 outline 은 표 래퍼에 잘린다').toBe('-2px');
  });

  test('고대비 모드: 검수 인박스 표(tabindex=-1)는 안쪽 offset outline 이 그려진다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await gotoReviewInbox(page);
    const table = page.getByTestId('review-inbox-table');
    await expect(table).toBeVisible();
    // 「다시 시도」 뒤 초점 복귀와 같은 경로 — 키보드 직후 스크립트로 초점을 주면 :focus-visible 이 된다
    await expect(table).toHaveAttribute('tabindex', '-1');
    await page.keyboard.press('Tab');
    await table.evaluate((node) => {
      node.focus();
    });
    const style = await readFocusStyle(table);

    expect(style.focusVisible).toBe(true);
    expect(style.outlineStyle).toBe('solid');
    expect(
      style.outlineOffset,
      '표 래퍼(overflow)가 바깥 outline 을 잘라 테두리가 안 보인다',
    ).toBe('-2px');
  });

  // WD-1020: CSS 규칙 단위 시험 — 주입 마크업으로 전역 규칙의 :not(:where(...)) 제외 문법만 본다.
  // 실제 컴포넌트가 이 role·tabindex 를 내는지는 아래 「실제 컴포넌트」 시험이 본다.
  test('고대비 모드(CSS 규칙 단위): tabindex=-1 인 role=menu 컨테이너에는 outline 이 없고 tabindex=0 인 것에는 있다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await gotoDatasetList(page);
    const [pStyle, tStyle] = await injectAndFocus(
      page,
      '<div id="fx-menu-programmatic" role="menu" tabindex="-1">a</div><div id="fx-menu-tabstop" role="menu" tabindex="0">b</div>',
      ['#fx-menu-programmatic', '#fx-menu-tabstop'],
    );
    expect(pStyle.focusVisible).toBe(true);
    expect(
      pStyle.outlineStyle,
      '프로그램 초점 전용(tabindex=-1) 메뉴 컨테이너에는 전역 outline(solid)이 그려지면 안 된다(브라우저 기본 auto 또는 none)',
    ).toMatch(/^(auto|none)$/);

    expect(tStyle.focusVisible).toBe(true);
    expect(
      tStyle.outlineStyle,
      'tabindex=0 인 진짜 Tab 멈춤은 초점이 보여야 한다',
    ).toBe('solid');
  });

  // WD-1020: CSS 규칙 단위 시험 — 실제 tabpanel 은 아래 「실제 컴포넌트」 시험이 본다.
  test('고대비 모드(CSS 규칙 단위): tabindex=-1 tabpanel 컨테이너에는 전역 outline 이 없다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await gotoDatasetList(page);
    // TabsControlsContent(role=tabpanel, tabIndex=-1)와 같은 마크업
    const [style] = await injectAndFocus(
      page,
      '<div id="fx-tabpanel" role="tabpanel" tabindex="-1">panel</div>',
      ['#fx-tabpanel'],
    );
    expect(style.focusVisible).toBe(true);
    expect(
      style.outlineStyle,
      '패널 전체에 전역 outline(solid)이 그려지면 안 된다',
    ).toMatch(/^(auto|none)$/);
  });

  test('고대비 모드: 전역 규칙은 낮은 특이도라 레이어 밖 outline:none(xyflow 등)이 이긴다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await gotoDatasetList(page);
    // xyflow 처럼 레이어 밖에서 outline:none 을 주는 (0,3,0) 규칙과 같은 모양
    const [style] = await injectAndFocus(
      page,
      '<div id="fx-own" class="fx-own fx-own2" tabindex="0"></div>',
      ['#fx-own'],
      '.fx-own.fx-own2:focus-visible { outline: none; }',
    );
    expect(style.focusVisible).toBe(true);
    expect(
      style.outlineStyle,
      '제외 목록의 ID 가 특이도를 올리면 이 outline:none 을 이겨 버린다',
    ).toBe('none');
  });

  /**
   * 파이프라인 편집기(수정 모드)를 열고 xyflow 노드·연결선 locator 를 돌려준다 (WD-1020).
   * 실제 앱 스택으로 xyflow CSS(레이어 밖 outline:none)와 전역 규칙의 특이도 싸움을 시험한다.
   * edit=false 면 「수정」을 누르지 않은 보기 화면(readOnly — 노드에 .selectable 없음)에 머문다 (WD-1040).
   */
  async function openPipelineEditor(page: Page, { edit = true }: { edit?: boolean } = {}) {
    await page.setViewportSize({ width: 1280, height: 720 });
    await grantPipelinePermissions(page);
    await setupPipelineEditorMocks(page, 1);
    await page.goto('/pipelines/1');
    if (edit) await page.getByRole('button', { name: '수정' }).click();
    const node = page.locator('.react-flow__node').first();
    const edge = page.locator('.react-flow__edge').first();
    await expect(node).toBeVisible();
    await expect(edge).toBeAttached();
    return { node, edge };
  }

  /**
   * 노드·연결선에 키보드 직후 초점을 줘 계산 스타일을 읽는다.
   * 연결선은 Tab 으로 안정적으로 닿기 어려워, Tab 한 번 뒤 focus() 로 :focus-visible 을 참으로 만든다.
   */
  async function focusAndReadNodeEdge(page: Page, node: Locator, edge: Locator) {
    await page.keyboard.press('Tab');
    await node.evaluate((n) => (n as HTMLElement).focus());
    const nodeStyle = await readFocusStyle(node);
    await edge.evaluate((n) => (n as unknown as HTMLElement).focus());
    const edgeStyle = await readFocusStyle(edge);
    return { nodeStyle, edgeStyle };
  }

  test('파이프라인 편집기 노드·연결선: 고대비에서는 CanvasText, 보통 모드에서는 링 색 초점 윤곽이 보인다 (WD-1020, WD-1040)', async ({
    authenticatedPage: page,
  }) => {
    // 편집기는 한 번만 연다 — 첫 읽기 전에 고대비를 켠다
    await page.emulateMedia({ forcedColors: 'active' });
    const { node, edge } = await openPipelineEditor(page);
    const forced = await focusAndReadNodeEdge(page, node, edge);
    expect(forced.nodeStyle.focusVisible).toBe(true);
    expect(forced.nodeStyle.outlineStyle, 'xyflow 노드 outline:none 을 이겨야 한다').toBe('solid');
    expect(forced.nodeStyle.outlineWidth).toBe('2px');
    expect(forced.edgeStyle.focusVisible).toBe(true);
    expect(forced.edgeStyle.outlineStyle, 'xyflow 연결선 outline:none 을 이겨야 한다').toBe('solid');
    expect(forced.edgeStyle.outlineWidth).toBe('2px');
    // offset 은 WD-1040 보통 모드 규칙에서 오고 고대비 블록은 색만 바꾼다 — 노드 4px, 연결선 2px 유지
    expect(forced.nodeStyle.outlineOffset).toBe('4px');
    expect(forced.edgeStyle.outlineOffset).toBe('2px');

    // 보통 모드로 돌려 같은 요소를 다시 읽는다 — xyflow 기본 outline:none 을 이기고 링 색 윤곽이 보여야 한다 (WD-1040)
    await page.emulateMedia({ forcedColors: 'none' });
    const normal = await focusAndReadNodeEdge(page, node, edge);
    expect(normal.nodeStyle.focusVisible).toBe(true);
    expect(normal.nodeStyle.outlineStyle).toBe('solid');
    expect(normal.nodeStyle.outlineWidth).toBe('2px');
    expect(normal.nodeStyle.outlineOffset, '노드는 선택 표시(카드 offset 2px)와 겹이 갈리게 4px').toBe('4px');
    expect(normal.edgeStyle.outlineStyle).toBe('solid');
    expect(normal.edgeStyle.outlineWidth).toBe('2px');
    expect(normal.edgeStyle.outlineOffset).toBe('2px');
    // 노드 outline 색이 --ring 토큰과 같아야 한다 — 임시 div 로 --ring 의 계산 색을 얻어 비교한다
    // 색은 노드에 초점이 있는 동안 읽어야 한다 — focusAndReadNodeEdge 는 마지막에 연결선으로 초점을 옮기므로 다시 노드에 초점을 준다
    await node.evaluate((n) => (n as HTMLElement).focus());
    const nodeFocus = await readFocusStyle(node);
    expect(nodeFocus.focusVisible, '색을 읽는 순간 노드가 :focus-visible 이어야 한다').toBe(true);
    const ringColor = await page.evaluate(() => {
      const d = document.createElement('div');
      d.style.outline = '2px solid var(--ring)';
      document.body.appendChild(d);
      const c = getComputedStyle(d).outlineColor;
      d.remove();
      return c;
    });
    expect(nodeFocus.outlineColor).toBe(ringColor);
  });

  test('파이프라인 편집기 노드: 마우스로 누른 노드(:focus-visible 아님)에는 초점 윤곽이 없다 (WD-1040)', async ({
    authenticatedPage: page,
  }) => {
    const { node } = await openPipelineEditor(page);
    await node.click();
    // 마우스 클릭은 :focus-visible 을 만들지 않는다 — 노드가 초점을 실제로 받았고(:focus) focus-visible 이 아닌지 먼저 확인해
    // 초점이 패널로 옮겨 가 단언이 저절로 통과하는 경우를 막는다
    const state = await node.evaluate((n) => ({
      focused: n.matches(':focus'),
      focusVisible: n.matches(':focus-visible'),
      outlineStyle: getComputedStyle(n).outlineStyle,
    }));
    expect(state.focused, '클릭한 노드가 초점을 받아야 한다').toBe(true);
    expect(state.focusVisible).toBe(false);
    expect(state.outlineStyle).toBe('none');
  });

  test('파이프라인 보기 화면(readOnly) 노드: .selectable 이 없어도 키보드 초점에 링 색 윤곽이 보인다 (WD-1040)', async ({
    authenticatedPage: page,
  }) => {
    // 「수정」을 누르지 않은 보기 화면 — elementsSelectable=false 라 노드에 .selectable 이 없지만 초점은 받는다
    const { node } = await openPipelineEditor(page, { edit: false });
    await expect(node).not.toHaveClass(/selectable/);
    // Tab 으로 키보드 입력 방식을 만든 뒤 focus() — 그래야 :focus-visible 이 참이 된다
    await page.keyboard.press('Tab');
    await node.evaluate((n) => (n as HTMLElement).focus());
    const style = await readFocusStyle(node);
    expect(style.focusVisible).toBe(true);
    expect(style.outlineStyle, '브라우저 기본(auto) 윤곽이 아니라 링 색 실선이어야 한다').toBe('solid');
    expect(style.outlineWidth).toBe('2px');
    expect(style.outlineOffset).toBe('4px');
  });

  test('고대비 모드: maplibre 컨트롤 버튼(라이브러리 CSS outline:none)도 outline 이 보인다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await gotoDatasetList(page);
    // 실제 라이브러리 CSS 를 그대로 주입한다 — 복사본이면 라이브러리 갱신을 따라가지 못한다
    await page.addStyleTag({
      // 패키지 해석으로 경로를 얻는다(cwd 가정 제거)
      path: createRequire(import.meta.url).resolve('maplibre-gl/dist/maplibre-gl.css'),
    });
    const [style] = await injectAndFocus(
      page,
      '<div class="maplibregl-ctrl maplibregl-ctrl-group"><button id="ml-btn" type="button">+</button></div>',
      ['#ml-btn'],
    );
    expect(style.focusVisible).toBe(true);
    expect(style.outlineStyle, 'maplibre outline:none 을 이겨야 한다').toBe('solid');
    expect(style.outlineWidth).toBe('2px');
    expect(style.outlineOffset).toBe('-2px');
  });

  /**
   * WD-1020: 제외 목록의 실제 컴포넌트 검증 헬퍼.
   * 주입 마크업 시험은 라이브러리가 role·tabindex 를 바꿔도 못 잡으므로, 실제 화면을 열어
   * 컨테이너의 role·tabindex="-1" 속성을 먼저 단언하고(바뀌면 여기서 먼저 실패),
   * 키보드 직후 컨테이너에 focus() 를 줘 :focus-visible 인 상태에서 outline 이 없음을 단언한다.
   * 이 헬퍼는 Tab 을 누르지 않는다 — 키보드 직후 상태가 필요하면 호출 전에 호출자가 직접 누른다
   * (Enter 로 연 목록 상자·확인 대화상자는 이미 키보드 입력 방식이라 생략한다. Tab 은 초점 가두기·RovingFocus 로 초점을 옮겨 버린다).
   * Radix 는 열릴 때 항목·첫 입력으로 초점을 옮기므로 컨테이너를 직접 focus() 해 규칙을 본다.
   */
  async function expectContainerNoOutline(container: Locator) {
    await expect(container).toHaveAttribute('tabindex', '-1');
    await container.evaluate((node) => (node as HTMLElement).focus());
    await expect(container).toBeFocused();
    const style = await readFocusStyle(container);
    expect(style.focusVisible, '키보드 직후 초점이라 :focus-visible 이어야 한다').toBe(true);
    expect(
      style.outlineStyle,
      '프로그램 초점 전용 컨테이너에는 전역 outline(solid)이 그려지면 안 된다',
    ).toMatch(/^(auto|none)$/);
  }

  test('고대비 모드(실제 컴포넌트): 사용자 메뉴 상자 role=menu 는 tabindex=-1 이다(제외 목록 전제)', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await gotoDatasetList(page);
    const trigger = page.getByRole('button', { name: /테스트 사용자/ }).first();
    await expect(trigger).toBeVisible();
    await page.keyboard.press('Tab');
    await trigger.focus();
    await page.keyboard.press('Enter');
    const menu = page.getByRole('menu');
    await expect(menu).toBeVisible();
    // Radix 메뉴(RovingFocusGroup)는 컨테이너에 초점이 오면 곧바로 첫 menuitem 으로 넘겨 컨테이너가 초점을 붙들지 못한다
    // (실측: focus() 직후 activeElement 가 menuitem, 컨테이너 :focus-visible 거짓). 그래서 컨테이너 초점 outline 은 볼 수 없고,
    // 실제 메뉴의 role·tabindex="-1" 속성(제외 목록의 전제)만 단언한다 — 라이브러리가 바꾸면 여기서 먼저 실패한다.
    // 메뉴 항목 초점 outline 은 아래 「사용자 메뉴 항목」 시험이 본다.
    await expect(menu).toHaveAttribute('role', 'menu');
    await expect(menu).toHaveAttribute('tabindex', '-1');
  });

  test('고대비 모드(실제 컴포넌트): Select 목록 상자 role=listbox 는 tabindex=-1 이고 outline 이 없다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await gotoDatasetList(page);
    // 데이터셋 목록 필터 Select — Radix Select 가 role=listbox 컨테이너를 연다
    const select = page.getByRole('combobox', { name: '상태 필터' });
    await expect(select).toBeVisible();
    await page.keyboard.press('Tab');
    await select.focus();
    await page.keyboard.press('Enter');
    const listbox = page.getByRole('listbox');
    await expect(listbox).toBeVisible();
    await expectContainerNoOutline(listbox);
  });

  test('고대비 모드(실제 컴포넌트): 복제 대화상자 role=dialog 는 tabindex=-1 이고 outline 이 없다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await grantDatasetPermissions(page);
    await setupDatasetDetailMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', []);
    await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
    await mockApi(page, 'GET', '/api/v1/datasets/1', createDatasetDetail({ id: 1 }));
    await page.goto('/data/datasets/1');
    await expect(page.getByRole('heading', { name: '테스트 데이터셋' })).toBeVisible();
    await page.getByRole('button', { name: '복제' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('heading', { name: '데이터셋 복제' })).toBeVisible();
    await page.keyboard.press('Tab');
    await expectContainerNoOutline(dialog);
  });

  // 삭제 확인(alertdialog)은 데이터셋 목록의 행 삭제 버튼으로 연다(WD-143 참조 집계 모킹 필요).
  test('고대비 모드(실제 컴포넌트): 삭제 확인 role=alertdialog 는 tabindex=-1 이고 outline 이 없다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await grantDatasetPermissions(page, [...DATASET_WRITE_PERMISSIONS, 'dataset:delete']);
    await mockDatasetReferences(page);
    await gotoDatasetList(page);
    const del = page.getByRole('button', { name: '삭제' }).first();
    await expect(del).toBeVisible();
    await page.keyboard.press('Tab');
    await del.focus();
    await page.keyboard.press('Enter');
    const alert = page.getByRole('alertdialog');
    await expect(alert).toBeVisible();
    await expectContainerNoOutline(alert);
  });

  test('고대비 모드(실제 컴포넌트): 검수 인박스 탭 패널 role=tabpanel 은 tabindex=-1 이고 outline 이 없다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await gotoReviewInbox(page);
    const panel = page.getByRole('tabpanel');
    await expect(panel).toBeVisible();
    await page.keyboard.press('Tab');
    await expectContainerNoOutline(panel);
  });

  test('고대비 모드: 사용자 메뉴 항목(tabindex=-1)은 outline 을 받고 메뉴 상자 안에서 잘리지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await gotoDatasetList(page);
    const trigger = page.getByRole('button', { name: /테스트 사용자/ }).first();
    await expect(trigger).toBeVisible();
    await page.keyboard.press('Tab');
    await trigger.focus();
    await expect(trigger).toBeFocused();
    await page.keyboard.press('Enter');
    const menu = page.getByRole('menu');
    await expect(menu).toBeVisible();
    const item = menu.getByRole('menuitem').first();
    await expect(item).toBeFocused();
    const itemStyle = await readFocusStyle(item);
    expect(itemStyle.outlineStyle).toBe('solid');
    const fit = await item.evaluate((node) => {
      const cs = getComputedStyle(node);
      const grow = parseFloat(cs.outlineWidth) + parseFloat(cs.outlineOffset);
      const r = node.getBoundingClientRect();
      const m = node.closest('[role=menu]')!.getBoundingClientRect();
      return {
        left: r.left - grow - m.left,
        right: m.right - (r.right + grow),
      };
    });
    expect(fit.left, 'outline 왼쪽이 메뉴 밖으로 잘린다').toBeGreaterThanOrEqual(0);
    expect(fit.right, 'outline 오른쪽이 메뉴 밖으로 잘린다').toBeGreaterThanOrEqual(0);
  });

  test('고대비 모드: 건너뛰기 링크 Enter 뒤 main(#main-content)에는 outline 이 없다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await gotoDatasetList(page);
    // 초점이 아직 body 라 첫 Tab 이 건너뛰기 링크에 닿는다
    await page.keyboard.press('Tab');
    const link = page.getByRole('link', { name: '본문으로 건너뛰기' });
    await expect(link).toBeFocused();
    await page.keyboard.press('Enter');
    const main = page.locator('main#main-content');
    await expect(main).toBeFocused();
    const style = await readFocusStyle(main);

    expect(style.focusVisible, '키보드로 건너뛰었으니 :focus-visible').toBe(
      true,
    );
    expect(
      style.outlineStyle,
      '스크립트 초점을 받는 main 전체에 테두리가 그려지면 안 된다',
    ).toBe('none');
  });

  test('고대비 모드: 다시 시도 초점을 받는 데이터셋 표(tabindex=-1, -outline-offset-2)는 outline 과 안쪽 offset 이 유지된다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await gotoDatasetList(page);
    const table = page.getByRole('table', { name: '데이터셋 목록' });
    await expect(table).toBeVisible();
    // 키보드 조작 직후 스크립트로 초점을 주면 :focus-visible 이 된다(「다시 시도」가 표에 초점을 옮기는 것과 같은 경로)
    await page.getByPlaceholder('데이터셋 검색...').click();
    await page.keyboard.press('Tab');
    await table.evaluate((node) => {
      node.tabIndex = -1;
      node.focus();
    });
    const style = await readFocusStyle(table);

    expect(
      style.focusVisible,
      '키보드 직후 초점이라 :focus-visible 이어야 한다',
    ).toBe(true);
    expect(style.outlineStyle, '고대비에서 outline 이 보여야 한다').not.toBe(
      'none',
    );
    expect(
      style.outlineOffset,
      '전역 규칙이 안쪽 offset 을 덮어쓰면 overflow 조상에 잘린다',
    ).toBe('-2px');
  });

  test('고대비 모드: 비밀번호 입력 초점이 outline 으로 보인다', async ({
    authMockedPage: page,
  }) => {
    await expectForcedColorsOutline(page, loginTarget);
  });

  for (const t of appTargets) {
    test(`고대비 모드: ${t.name} 초점이 outline 으로 보인다`, async ({
      authenticatedPage: page,
    }) => {
      await expectForcedColorsOutline(page, t);
    });
  }

  test('보통 모드: 비밀번호 입력은 outline 이 없고 ring(box-shadow)이 그대로다', async ({
    authMockedPage: page,
  }) => {
    await expectNormalRing(page, loginTarget);
  });

  for (const t of appTargets) {
    test(`보통 모드: ${t.name} — outline 이 없고 ring(box-shadow)이 그대로다`, async ({
      authenticatedPage: page,
    }) => {
      await expectNormalRing(page, t);
    });
  }
});

/**
 * 스위치에 키보드 초점을 준다 — Shift+Tab·Tab 왕복으로 :focus-visible 을 확정한다(WD-924 시험과 같은 방식).
 * :focus-visible 이 아니면 「outline 없음」 단언이 공허하게 통과하므로 여기서 확인한다.
 */
async function focusSwitchByKeyboard(page: Page, root: Locator) {
  await expect(root).toBeVisible();
  await root.focus();
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('Tab');
  await expect(root).toBeFocused();
  const rootFocus = await readFocusStyle(root);
  expect(rootFocus.focusVisible, '스위치가 키보드 초점(:focus-visible) 상태가 아니다').toBe(true);
  return { rootFocus, track: root.locator('[data-slot="switch-track"]') };
}

/** 스위치 키보드 초점 위치 (WD-1091) — 고대비에서 바깥선이 32×32 루트가 아니라 알약(track)에 그려지는지 본다. */
test.describe('스위치 초점 바깥선 위치 (WD-1091)', () => {
  /** 활동 목록의 첫 스위치에 키보드 초점을 준다. */
  async function focusFirstSwitch(page: Page) {
    await mockProactivePermissions(page);
    await setupJobListMocks(page, 2);
    await page.goto('/ai-insights/jobs');
    return focusSwitchByKeyboard(page, page.getByRole('switch').first());
  }

  test('고대비 모드에서 스위치 초점 바깥선이 알약(track)에 그려진다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    const { rootFocus, track } = await focusFirstSwitch(page);

    expect(rootFocus.outlineStyle, '루트(32×32 원형 히트 상자)에 바깥선이 그려졌다 (WD-1091)').toBe('none');
    // 고대비에서는 알약의 outline 전환을 꺼 초점 즉시 2px solid / offset 1px 이어야 한다 — poll 없이 바로 읽는다.
    // 색은 고대비가 시스템 색으로 덮어써 단언하지 않는다.
    const trackFocus = await readFocusStyle(track);
    expect(
      [trackFocus.outlineStyle, trackFocus.outlineWidth, trackFocus.outlineOffset],
      '알약(track)에 2px solid / offset 1px 바깥선이 없다 (WD-1091)',
    ).toEqual(['solid', '2px', '1px']);
  });

  test('보통 모드에서는 알약에 outline 이 없다(ring 만)', async ({ authenticatedPage: page }) => {
    await page.emulateMedia({ forcedColors: 'none' });
    const { track } = await focusFirstSwitch(page);
    // WD-924 의 3px ring 은 그대로여야 한다 — toHaveCSS 가 전환이 끝날 때까지 다시 읽는다.
    await expect(track, '알약(track)의 WD-924 ring 이 없다').toHaveCSS('box-shadow', /0px 0px 0px 3px/);
    await expect(track, '보통 모드 알약에 outline 이 생겼다 (WD-1091)').toHaveCSS('outline-style', 'none');
  });
});

/**
 * 켜진 잡·꺼진 잡 하나씩인 활동 목록을 연다(기본 고대비, 초점은 주지 않는다) — 스위치를 누르면 서버 응답으로 상태가
 * 돌아오므로 누르지 않고 두 상태를 나란히 읽는다. WD-1137·WD-1136 이 함께 쓴다.
 */
async function openOnOffSwitches(page: Page, forcedColors: 'active' | 'none' = 'active') {
  await page.emulateMedia({ forcedColors });
  await mockProactivePermissions(page);
  await setupJobListMocks(page, [
    createJob({ id: 1, name: '켜진 잡', enabled: true }),
    createJob({ id: 2, name: '꺼진 잡', enabled: false }),
  ]);
  await page.goto('/ai-insights/jobs');
  const switches = page.getByRole('switch');
  await expect(switches).toHaveCount(2);
  await expect(switches.nth(0)).toHaveAttribute('data-state', 'checked');
  await expect(switches.nth(1)).toHaveAttribute('data-state', 'unchecked');
  return { on: switches.nth(0), off: switches.nth(1) };
}

/** 고대비 스위치 상태 구분 (WD-1137) — 고대비가 손잡이·알약 배경을 Canvas 로 지워 켜짐·꺼짐이 같아 보이던 문제. */
test.describe('고대비 스위치 켜짐·꺼짐 구분 (WD-1137)', () => {
  /** 알약·손잡이의 고대비 관련 계산 스타일을 한 번에 읽는다. */
  async function readSwitchColors(root: Locator) {
    return root.evaluate((el) => {
      const track = el.querySelector('[data-slot="switch-track"]') as HTMLElement;
      const thumb = el.querySelector('[data-slot="switch-thumb"]') as HTMLElement;
      const t = getComputedStyle(track);
      const h = getComputedStyle(thumb);
      return {
        state: el.getAttribute('data-state'),
        rootOpacity: getComputedStyle(el).opacity,
        trackBg: t.backgroundColor,
        trackBorder: t.borderTopColor,
        trackShadow: t.boxShadow,
        thumbBg: h.backgroundColor,
        thumbShadow: h.boxShadow,
        thumbAdjust: h.forcedColorAdjust,
      };
    });
  }


  test('고대비 모드에서 스위치 손잡이가 보이고 켜짐·꺼짐 알약 색이 다르다', async ({
    authenticatedPage: page,
  }) => {
    const sw = await openOnOffSwitches(page);
    const on = await readSwitchColors(sw.on);
    const off = await readSwitchColors(sw.off);
    for (const c of [on, off]) {
      // 손잡이가 강제 색(Canvas)으로 지워지지 않아야 하고, 알약 배경과 다른 색이어야 보인다
      expect(c.thumbAdjust, `${c.state}: 손잡이가 고대비 강제 색으로 덮인다 (WD-1137)`).toBe('none');
      expect(c.thumbBg, `${c.state}: 손잡이 색이 알약 배경과 같아 보이지 않는다 (WD-1137)`).not.toBe(c.trackBg);
      // 강제 색을 껐으니 알약 테두리를 직접 줘야 한다 — 투명이면 꺼짐 알약 윤곽이 사라진다
      expect(c.trackBorder, `${c.state}: 알약 테두리가 투명이다 (WD-1137)`).not.toBe('rgba(0, 0, 0, 0)');
      // 강제 색을 끄면 shadow-xs 가 되살아난다 — 팔레트 밖 회색 그림자가 없어야 한다
      expect([c.trackShadow, c.thumbShadow], `${c.state}: 고대비에서 그림자가 그려진다 (WD-1137)`).toEqual(['none', 'none']);
    }
    // 위치만이 아니라 채움으로도 구분 — 켜짐·꺼짐 알약 배경과 손잡이 색이 달라야 한다
    expect(on.trackBg, '켜짐·꺼짐 알약 배경이 같다 (WD-1137)').not.toBe(off.trackBg);
    expect(on.thumbBg, '켜짐·꺼짐 손잡이 색이 같다 (WD-1137)').not.toBe(off.thumbBg);
  });

  test('고대비 모드에서 키보드 초점을 받은 스위치에 테마색 ring(box-shadow)이 되살아나지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const sw = await openOnOffSwitches(page);
    const { track: trackLocator } = await focusSwitchByKeyboard(page, sw.on);
    const c = await readSwitchColors(sw.on);
    // WD-924 ring(3px box-shadow)이 2px CanvasText outline 아래로 비치면 안 된다 — 초점 표시는 outline 만
    expect(c.trackShadow, '고대비 초점에서 알약에 ring(box-shadow)이 그려진다 (WD-1137)').toBe('none');
    const track = await readFocusStyle(trackLocator);
    expect([track.outlineStyle, track.outlineWidth], '알약의 초점 outline 이 사라졌다 (WD-1091)').toEqual(['solid', '2px']);
  });

  /**
   * 고대비에서 `color: <시스템 색 이름>`(GrayText, Highlight 등)인 요소의 계산 색 — 시스템 색 이름은 계산 시 rgb 로 풀리므로
   * 스위치 색과 같은 표기로 비교하려고 페이지 안에서 직접 읽는다.
   */
  async function readSystemColor(page: Page, name: string) {
    return page.evaluate((colorName) => {
      const probe = document.createElement('span');
      probe.style.color = colorName;
      document.body.appendChild(probe);
      const color = getComputedStyle(probe).color;
      probe.remove();
      return color;
    }, name);
  }

  /** 사용자 상세 자기 계정(id 2) — 켜진 채 잠긴 실제 비활성 스위치(UserDetailPage)가 나오는 화면을 고대비로 연다. */
  async function openOwnAccountSwitch(page: Page) {
    await page.emulateMedia({ forcedColors: 'active' });
    await setupAdminAuth(page);
    await mockApi(page, 'GET', '/api/v1/roles', [createRole({ id: 1, name: 'USER', isSystem: true })]);
    await mockApi(page, 'GET', '/api/v1/users/2', createUserDetail({ id: 2, name: '관리자', username: 'admin@example.com' }));
    await page.goto('/admin/users/2');
    return page.locator('[data-testid="suspend-lock-trigger"] [data-slot="switch"]');
  }

  test('실제 비활성 스위치 — 사용자 상세 자기 계정(켜짐·비활성)', async ({ authenticatedPage: page }) => {
    const sw = await openOwnAccountSwitch(page);
    await expect(sw, '실제 마크업이 disabled 가 아니다').toBeDisabled();
    await expect(sw).toHaveAttribute('data-disabled', '');
    await expect(sw).toHaveAttribute('data-state', 'checked');
    const grayText = await readSystemColor(page, 'GrayText');
    // 알약은 transition-all 이라 색이 150ms 동안 옮겨 간다 — 고정 대기 없이 GrayText 에 닿을 때까지 기다린다
    // 마지막으로 읽은 색을 아래 단언에 재사용한다(poll 통과 시점의 값)
    let on!: Awaited<ReturnType<typeof readSwitchColors>>;
    await expect
      .poll(async () => {
        on = await readSwitchColors(sw);
        return on.trackBg;
      }, { message: '비활성 켜짐 알약이 GrayText 로 채워지지 않았다 (WD-1137)' })
      .toBe(grayText);
    // opacity-50 은 시스템 색을 Canvas 와 섞어 팔레트 밖 색을 만든다 — 고대비에서는 되돌린다
    expect(on.rootOpacity, '비활성 스위치에 반투명이 남았다 (WD-1137)').toBe('1');
    // 켜짐 비활성: 손잡이는 알약과 다른 색 — 켜짐·꺼짐이 채움으로도 구분된다
    expect(on.thumbBg, '비활성 켜짐 손잡이가 알약 배경과 같다 (WD-1137)').not.toBe(on.trackBg);
  });

  test('실제 비활성 스위치 — 미연결 채널(꺼짐·비활성)', async ({ authenticatedPage: page }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await mockApi(page, 'GET', '/api/v1/channels/settings', [
      { channel: 'KAKAO', enabled: false, connected: false, needsReauth: false, displayAddress: '카카오톡', oauthStartUrl: null, workspaceId: null },
    ]);
    await page.goto('/settings/channels');
    const sw = page.getByRole('switch', { name: /카카오.*채널 활성화/ });
    await expect(sw, '실제 마크업이 disabled 가 아니다').toBeDisabled();
    await expect(sw).toHaveAttribute('data-state', 'unchecked');
    const grayText = await readSystemColor(page, 'GrayText');
    // 마지막으로 읽은 색을 아래 단언에 재사용한다(poll 통과 시점의 값)
    let off!: Awaited<ReturnType<typeof readSwitchColors>>;
    await expect
      .poll(async () => {
        off = await readSwitchColors(sw);
        return [off.trackBorder, off.thumbBg];
      }, { message: '비활성 꺼짐 테두리·손잡이가 GrayText 가 아니다 (WD-1137)' })
      .toEqual([grayText, grayText]);
    expect(off.rootOpacity, '비활성 스위치에 반투명이 남았다 (WD-1137)').toBe('1');
  });

  test('꺼짐 손잡이에 Canvas 틈 (WD-1150)', async ({ authenticatedPage: page }) => {
    const sw = await openOnOffSwitches(page);
    const thumb = sw.off.locator('[data-slot="switch-thumb"]');
    // 꺼짐 CanvasText 손잡이가 CanvasText 테두리에 붙어 경계가 안 보이던 문제 — Canvas outline 으로 틈을 둔다
    await expect(thumb).toHaveCSS('outline-style', 'solid');
    await expect(thumb).toHaveCSS('outline-width', '1px');
    // 링이 알약 테두리를 덮지 않도록 손잡이 안쪽에 그린다
    await expect(thumb).toHaveCSS('outline-offset', '-1px');
    // 보통 모드에는 이 규칙이 없다
    await page.emulateMedia({ forcedColors: 'none' });
    await expect(thumb).toHaveCSS('outline-style', 'none');
  });

  /**
   * 요청 중 채널 스위치 색 검증 — 같은 화면의 건드리지 않은 활성 스위치(REF, 요청한 값과 같은 상태)와 색이 정확히 같아야 한다.
   * 왜: 「GrayText 가 아님」만 보면 어떤 색이든 통과한다. 활성 스위치와 동일하다고 단언해야 busy 가 비활성 색을 쓰지 않음이 확정된다.
   * requested: 클릭 뒤 보이는 요청한 값. 반대 상태의 KAKAO 를 누르고, 그 상태의 EMAIL(참조)과 비교한다.
   */
  async function expectBusyMatchesEnabledSwitch(page: Page, requestedChecked: boolean) {
    await page.emulateMedia({ forcedColors: 'active' });
    await mockApi(page, 'GET', '/api/v1/channels/settings', [
      { channel: 'KAKAO', enabled: !requestedChecked, connected: true, needsReauth: false, displayAddress: '카카오톡', oauthStartUrl: null, workspaceId: null },
      { channel: 'EMAIL', enabled: requestedChecked, connected: true, needsReauth: false, displayAddress: 'test@example.com', oauthStartUrl: null, workspaceId: null },
    ]);
    const held = await holdApi(page, 'PATCH', '/api/v1/channels/settings/KAKAO/preference', 204, {});
    await page.goto('/settings/channels');
    const sw = page.getByRole('switch', { name: /카카오.*채널 활성화/ });
    const ref = page.getByRole('switch', { name: /이메일.*채널 활성화/ });
    const state = requestedChecked ? 'checked' : 'unchecked';
    await expect(sw).toHaveAttribute('data-state', requestedChecked ? 'unchecked' : 'checked');
    await expect(ref).toHaveAttribute('data-state', state);
    const grayText = await readSystemColor(page, 'GrayText');
    await sw.click();
    // 요청 중: 진짜 비활성이 아니라 busy — disabled 속성이 없다
    await expect(sw).toHaveAttribute('aria-busy', 'true');
    await expect(sw).toHaveAttribute('data-state', state);
    await expect(sw, '요청 중 스위치가 disabled 로 바뀌었다 (WD-1150)').not.toHaveAttribute('disabled');
    // 알약 색 전환(150ms)이 끝나면 요청한 상태의 활성 스위치와 색이 같아야 한다
    const refColors = await readSwitchColors(ref);
    // 마지막으로 읽은 색을 아래 단언에 재사용한다(poll 통과 시점의 값)
    let c!: Awaited<ReturnType<typeof readSwitchColors>>;
    await expect
      .poll(async () => {
        c = await readSwitchColors(sw);
        return [c.trackBg, c.trackBorder, c.thumbBg];
      }, { message: `요청 중 ${state} 스위치가 활성 ${state} 스위치와 색이 다르다 (WD-1150)` })
      .toEqual([refColors.trackBg, refColors.trackBorder, refColors.thumbBg]);
    expect([c.trackBg, c.trackBorder, c.thumbBg], '요청 중 색에 GrayText 가 섞였다 (WD-1150)').not.toContain(grayText);
    // 켜짐 요청은 Highlight 알약을 유지한다 — 참조 스위치도 같은 색이므로 GrayText 가 아님과 함께 켜짐 채움이 유지됨을 확인
    if (requestedChecked) {
      const highlight = await readSystemColor(page, 'Highlight');
      expect(c.trackBg, '켜짐 요청 중 알약이 Highlight 가 아니다 (WD-1150)').toBe(highlight);
    }
    held.release();
    await expect(sw).not.toHaveAttribute('aria-busy');
  }

  test('요청 중인 채널 스위치(꺼짐 요청)는 활성 꺼짐 스위치와 같은 색 (WD-1150)', async ({ authenticatedPage: page }) => {
    await expectBusyMatchesEnabledSwitch(page, false);
  });

  test('요청 중인 채널 스위치(켜짐 요청)는 활성 켜짐 스위치와 같은 색(Highlight) (WD-1150)', async ({ authenticatedPage: page }) => {
    await expectBusyMatchesEnabledSwitch(page, true);
  });

  /**
   * 요청 중인 사용자 상세 활성 스위치 색 (WD-1168) — 「GrayText 가 아님」만으로는 어떤 색이든 통과하므로,
   * 요청을 푼 뒤 같은 상태가 된 유휴 스위치 색과 정확히 같은지까지 단언한다(WD-1150 헬퍼와 같은 방법).
   * 사용자 상세에는 스위치가 하나뿐이라 참조를 같은 스위치의 유휴 상태에서 얻는다.
   * 대상은 id 1 — id 2 는 setupAdminAuth 의 자기 계정이라 정지 잠금(suspendLock) 갈래의 진짜 disabled 로 렌더링된다.
   */
  test('요청 중인 사용자 상세 활성 스위치는 유휴 스위치와 같은 색이고 GrayText 가 아니다 (WD-1168)', async ({ authenticatedPage: page }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await setupAdminAuth(page);
    await mockApi(page, 'GET', '/api/v1/roles', [createRole({ id: 1, name: 'USER', isSystem: true })]);
    await mockApi(page, 'GET', '/api/v1/users/1', createUserDetail({ id: 1, name: '테스트 사용자', username: 'user1', isActive: true }));
    const held = await holdApi(page, 'PUT', '/api/v1/users/1/active', 200, { id: 1, isActive: false });
    await page.goto('/admin/users/1');

    const sw = page.getByRole('switch', { name: '이 워크스페이스에서 활성' });
    await expect(sw).toBeChecked();
    await sw.click();
    await page.getByRole('alertdialog').getByRole('button', { name: '정지' }).click();
    // 닫힘 오버레이(z-50, 200ms)가 남아 있으면 아래 클릭이 오버레이에 먹힌다
    await expect(page.getByRole('alertdialog')).toHaveCount(0);

    await expect(sw).toHaveAttribute('aria-busy', 'true');
    await expect(sw, '요청 중 스위치가 disabled 로 바뀌었다 (WD-1168)').not.toHaveAttribute('disabled');
    await expect(sw).toHaveAttribute('data-state', 'unchecked');
    const grayText = await readSystemColor(page, 'GrayText');
    // 알약 색 전환(150ms)이 끝날 때까지 기다린다 — 마지막으로 읽은 색을 아래 단언에 쓴다.
    // 전환 중간 값을 그대로 비교에 쓰면 유휴 색과 어긋나 오패스가 나므로, 연속 두 판독이 같아질 때(안정화)까지 센다.
    let prevBusy = '';
    let busy!: Awaited<ReturnType<typeof readSwitchColors>>;
    await expect
      .poll(async () => {
        busy = await readSwitchColors(sw);
        const same = busy.trackBg === prevBusy;
        prevBusy = busy.trackBg;
        return same && busy.trackBg !== grayText;
      }, { message: '요청 중 알약이 고정되지 않았다 (WD-1168)' })
      .toBe(true);
    expect([busy.trackBg, busy.trackBorder, busy.thumbBg], '요청 중 색에 GrayText 가 섞였다 (WD-1168)')
      .not.toContain(grayText);
    // 반투명(opacity < 1)이 busy 상태에 남으면 색 비교만으로는 통과하므로 루트 불투명도까지 단언한다(WD-1168)
    expect(busy.rootOpacity, '요청 중 스위치 루트에 반투명이 남았다 (WD-1168)').toBe('1');

    // 요청을 풀면 같은 상태(정지)의 유휴 스위치가 된다 — busy 색이 그 유휴 색과 같아야 한다
    held.release();
    await expect(sw).not.toHaveAttribute('aria-busy');
    await expect(sw).toHaveAttribute('data-state', 'unchecked');
    let idle!: Awaited<ReturnType<typeof readSwitchColors>>;
    await expect
      .poll(async () => {
        idle = await readSwitchColors(sw);
        return [idle.trackBg, idle.trackBorder, idle.thumbBg];
      }, { message: '유휴 스위치 색이 고정되지 않았다 (WD-1168)' })
      .toEqual([busy.trackBg, busy.trackBorder, busy.thumbBg]);
  });
});

/**
 * 고대비 스위치 히트 상자 테두리 (WD-1136) — 루트(32×32)의 투명 테두리를 고대비가 시스템 색으로 그려
 * 초점 없는 스위치 둘레에 원이 보이던 문제. 켜짐·꺼짐 둘 다 원이 없고, 보통 모드 레이아웃은 그대로여야 한다.
 */
test.describe('고대비 스위치 히트 상자 테두리 (WD-1136)', () => {
  test('고대비 모드에서 초점 없는 스위치 루트에 테두리가 그려지지 않는다', async ({
    authenticatedPage: page,
  }) => {
    const { on, off } = await openOnOffSwitches(page);
    for (const [name, root] of [['켜짐', on], ['꺼짐', off]] as const) {
      const widths = await root.evaluate((el) => {
        const s = getComputedStyle(el);
        return [s.borderTopWidth, s.borderRightWidth, s.borderBottomWidth, s.borderLeftWidth];
      });
      expect(widths, `${name} 스위치 루트에 테두리가 남았다 (WD-1136)`).toEqual(['0px', '0px', '0px', '0px']);
    }
  });

  test('루트 테두리를 빼도 히트 상자 32×32 와 알약 위치는 그대로다', async ({
    authenticatedPage: page,
  }) => {
    const { on: root } = await openOnOffSwitches(page, 'none');
    // 루트·알약 상자를 한 번에 읽는다
    const { box, track } = await root.evaluate((el) => {
      const r = el.getBoundingClientRect();
      const t = (el.querySelector('[data-slot="switch-track"]') as HTMLElement).getBoundingClientRect();
      return { box: [r.width, r.height], track: { left: t.left - r.left, top: t.top - r.top, width: t.width } };
    });
    expect(box, '루트 히트 상자가 32×32 가 아니다 (WD-1136)').toEqual([32, 32]);
    expect(Math.round(track.left), '알약 left 오프셋 (WD-1136)').toBe(0);
    expect(Math.round(track.top), '알약 top 오프셋 (WD-1136)').toBe(7);
    expect(Math.round(track.width), '알약 너비 (WD-1136)').toBe(32);
  });
});
