import type { Locator, Page } from '@playwright/test';

import {
  createDatasetDetail,
  createDatasets,
} from '../../factories/dataset.factory';
import { createPropertyReviewItem } from '../../factories/reviewItem.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import {
  grantDatasetPermissions,
  setupDatasetDetailMocks,
  setupDatasetMocks,
} from '../../fixtures/dataset.fixture';
import { readFocusStyle, tabUntilFocused } from '../../fixtures/keyboard-focus';

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

  test('고대비 모드: 검수 인박스 표(tabindex=-1)는 안쪽 offset outline 이 그려진다', async ({
    authenticatedPage: page,
  }) => {
    await page.emulateMedia({ forcedColors: 'active' });
    await mockApi(page, 'GET', '/api/v1/auth/me/permissions', [
      'dataset:read',
      'dataset:write',
    ]);
    await mockApi(page, 'GET', '/api/v1/graphrag/review-items', []);
    await page.goto('/knowledge-graph/review');
    const table = page.getByTestId('review-inbox-table');
    await expect(table).toBeVisible();
    // 「다시 시도」 뒤 초점 복귀와 같은 경로 — 키보드 직후 스크립트로 초점을 주면 :focus-visible 이 된다
    await page.keyboard.press('Tab');
    await table.evaluate((node) => {
      node.tabIndex = -1;
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

  test('고대비 모드: tabindex=-1 인 role=menu 컨테이너에는 outline 이 없고 tabindex=0 인 것에는 있다', async ({
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
    expect(pStyle.outlineWidth === '2px' && pStyle.outlineStyle === 'solid').toBe(false);


    expect(tStyle.focusVisible).toBe(true);
    expect(
      tStyle.outlineStyle,
      'tabindex=0 인 진짜 Tab 멈춤은 초점이 보여야 한다',
    ).toBe('solid');
  });

  test('고대비 모드: tabindex=-1 tabpanel 컨테이너에는 전역 outline 이 없다', async ({
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
