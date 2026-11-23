import type { Locator, Page } from '@playwright/test';

import {
  createDatasetDetail,
  createDatasets,
} from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import {
  grantDatasetPermissions,
  setupDatasetDetailMocks,
  setupDatasetMocks,
} from '../../fixtures/dataset.fixture';
import { tabUntilFocused } from '../../fixtures/keyboard-focus';

/**
 * 고대비(forced-colors) 모드 전역 키보드 초점 표시 (WD-949, WD-944 후속)
 * - 초점 표시를 box-shadow ring 으로만 그리고 outline-none 을 둔 부품(shadcn 입력·표 정렬 헤더·UserNav 등)은
 *   고대비 모드에서 box-shadow 가 그려지지 않아 키보드 초점이 보이지 않았다.
 * - index.css 의 레이어 밖 forced-colors 규칙이 모든 :focus-visible 에 시스템 색 outline 을 그린다.
 *   보통 모드는 그대로(outline 없음 + ring).
 */

/** 초점 표시에 관련된 계산 스타일을 읽는다 */
async function readFocusStyle(el: Locator) {
  return el.evaluate((node) => {
    const cs = getComputedStyle(node);
    return {
      focusVisible: node.matches(':focus-visible'),
      outlineStyle: cs.outlineStyle,
      outlineWidth: cs.outlineWidth,
      outlineOffset: cs.outlineOffset,
      boxShadow: cs.boxShadow,
    };
  });
}

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
  test('고대비 모드: 안쪽 offset(-outline-offset-2)을 쓰는 데이터셋 표는 offset 이 유지된다', async ({
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
