import type { Locator, Page } from '@playwright/test';

import { createDashboard, createSavedQuery } from '../factories/analytics.factory';
import { createPipelineDetail } from '../factories/pipeline.factory';
import { setupDashboardEditorMocks, setupQueryEditorMocks } from '../fixtures/analytics.fixture';
import { mockApi } from '../fixtures/api-mock';
import { expect, test } from '../fixtures/auth.fixture';
import {
  expectInsideHorizontally,
  expectNoHorizontalOverflow,
  expectNoOverflowWithin,
  expectNoTitleOnHover,
  expectTruncatedWithHoverTitle,
  LONG_UNBROKEN_NAME,
  NARROW_DESKTOP_VIEWPORT,
} from '../fixtures/long-unbroken-name';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../fixtures/pipeline.fixture';
import { expectLongValueTruncated, expectValueNotTruncated } from '../fixtures/table-layout';

/**
 * 파이프라인·저장된 쿼리·대시보드 상세 제목 — 띄어쓰기 없는 긴 이름 (WD-656)
 * - 세 화면은 툴바 안 한 줄 제목이라 줄바꿈 대신 말줄임을 쓴다. 원칙(WD-517·WD-634)대로 잘린 제목은 title 로 전체 이름을 보여야 하고,
 *   짧은 이름은 잘리지 않아야 한다.
 * - 파이프라인은 공용 Breadcrumb 의 마지막 항목(잘렸을 때만 title)과 편집 모드 이름 입력칸도 함께 본다.
 * - WD-663: 파이프라인·저장된 쿼리 제목은 TruncatedTitle 로 바꿔 잘렸을 때만(hover 때 재서) title 을 단다.
 *   대시보드 제목은 이 이슈 범위 밖이라 늘 title 을 두는 기존 동작을 그대로 본다.
 */

const SHORT_NAME = '일일 출동 집계';

interface Scenario {
  label: string;
  path: string;
  /** ReactFlow 캔버스가 있어 main 전체 넘침 검사 대신 머리글 범위로 좁혀 보는 화면 */
  canvas?: boolean;
  /** name 을 이름으로 하는 상세 응답을 모킹한다 */
  setup: (page: Page, name: string) => Promise<void>;
  /** 화면에 보이는 제목 요소 */
  title: (page: Page, name: string) => Locator;
  /** 제목이 TruncatedTitle 이라 잘렸을 때만(hover 때) title 을 단다 (WD-663) */
  titleOnlyWhenTruncated?: boolean;
}

const SCENARIOS: Scenario[] = [
  {
    label: '파이프라인',
    path: '/pipelines/1',
    canvas: true,
    titleOnlyWhenTruncated: true,
    setup: async (page, name) => {
      await setupPipelineEditorMocks(page, 1);
      await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, name }));
    },
    // 조회 모드 머리글의 이름(시각 제목) — h1 은 sr-only 라 머리글 span 을 본다
    title: (page, name) => page.locator('header').getByText(name, { exact: true }),
  },
  {
    label: '저장된 쿼리',
    path: '/analytics/queries/1',
    titleOnlyWhenTruncated: true,
    setup: async (page, name) => {
      await setupQueryEditorMocks(page, 1);
      await mockApi(page, 'GET', '/api/v1/analytics/queries/1', createSavedQuery({ id: 1, name }));
    },
    title: (page) => page.getByRole('heading', { level: 1 }),
  },
  {
    label: '대시보드',
    path: '/analytics/dashboards/1',
    setup: async (page, name) => {
      await setupDashboardEditorMocks(page, 1);
      await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, name }));
    },
    title: (page) => page.getByRole('heading', { level: 1 }),
  },
];

/** 화면 넘침 검사 — 캔버스 화면은 main 대신 머리글·breadcrumb 범위만 본다(a11y-reflow-motion #402 와 같은 이유) */
async function expectPageFits(page: Page, s: Scenario) {
  await expectNoHorizontalOverflow(page, { checkMain: !s.canvas });
  if (s.canvas) {
    await expectNoOverflowWithin(page.locator('header'), '머리글');
    await expectNoOverflowWithin(page.getByRole('navigation', { name: '현재 위치' }), 'breadcrumb');
  }
}

for (const s of SCENARIOS) {
  test.describe(`${s.label} 상세 긴 이름 제목 (WD-656)`, () => {
    test.beforeEach(async ({ authenticatedPage: page }) => {
      await page.setViewportSize(NARROW_DESKTOP_VIEWPORT);
    });

    test('1024px 에서 띄어쓰기 없는 100자 이름이 넘치지 않고 말줄임 + 전체 이름 title 로 보인다', async ({
      authenticatedPage: page,
    }) => {
      await s.setup(page, LONG_UNBROKEN_NAME);
      await page.goto(s.path);

      const title = s.title(page, LONG_UNBROKEN_NAME);
      await expect(title).toHaveText(LONG_UNBROKEN_NAME);
      await expectPageFits(page, s);
      const box = await title.boundingBox();
      expect(box!.x + box!.width, '제목 오른쪽 끝이 화면 밖').toBeLessThanOrEqual(NARROW_DESKTOP_VIEWPORT.width);
      if (s.titleOnlyWhenTruncated) await expectTruncatedWithHoverTitle(title, LONG_UNBROKEN_NAME);
      else await expectLongValueTruncated(title, LONG_UNBROKEN_NAME);

      // 공용 Breadcrumb 를 쓰는 화면(파이프라인)은 마지막 항목도 말줄임 + (hover 때) title 이다
      const nav = page.getByRole('navigation', { name: '현재 위치' });
      if ((await nav.count()) > 0) {
        const current = nav.locator('[aria-current="page"]');
        await expectTruncatedWithHoverTitle(current, LONG_UNBROKEN_NAME);
        const currentBox = await current.boundingBox();
        const navBox = await nav.boundingBox();
        expect(currentBox!.x + currentBox!.width).toBeLessThanOrEqual(navBox!.x + navBox!.width + 1);
      }
    });

    test('짧은 이름은 잘리지 않는다', async ({ authenticatedPage: page }) => {
      await s.setup(page, SHORT_NAME);
      await page.goto(s.path);

      const title = s.title(page, SHORT_NAME);
      await expect(title).toHaveText(SHORT_NAME);
      // TruncatedTitle 제목은 hover 해도 툴팁이 없다(WD-663)
      if (s.titleOnlyWhenTruncated) await expectNoTitleOnHover(title);
      else await expectValueNotTruncated(title);
      const nav = page.getByRole('navigation', { name: '현재 위치' });
      if ((await nav.count()) > 0) {
        await expectNoTitleOnHover(nav.locator('[aria-current="page"]'));
      }
      await expectPageFits(page, s);
    });
  });
}

test.describe('파이프라인 머리글 동작 버튼·편집 모드 (WD-656)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await page.setViewportSize(NARROW_DESKTOP_VIEWPORT);
    await grantPipelinePermissions(page);
    await SCENARIOS[0].setup(page, LONG_UNBROKEN_NAME);
    await page.goto('/pipelines/1');
  });

  test('긴 이름이어도 머리글 동작 버튼이 화면·머리글 안에 보이고, 편집 모드 입력칸은 잘렸을 때만 title 이 있다', async ({
    authenticatedPage: page,
  }) => {
    const header = page.locator('header');
    // 조회 모드 — 실행·수정 버튼이 머리글 밖으로 밀려 잘리지 않는다
    for (const name of ['실행', '수정']) {
      const button = header.getByRole('button', { name, exact: true });
      await expect(button).toBeInViewport({ ratio: 1 });
      await expectInsideHorizontally(button, header, `「${name}」 버튼`);
    }

    // 편집 모드 — 입력칸도 WD-517 원칙을 따른다(WD-669): 잘린 긴 이름은 hover 때 전체 이름 title, 잘리지 않은 짧은 이름은 title 없음.
    // 저장·취소 버튼도 화면 안에 보인다
    await header.getByRole('button', { name: '수정', exact: true }).click();
    const input = header.getByRole('textbox', { name: '파이프라인 이름' });
    await expect(input).toHaveValue(LONG_UNBROKEN_NAME);
    await expectTruncatedWithHoverTitle(input, LONG_UNBROKEN_NAME);

    // hover 한 채로 짧은 이름으로 고치면 입력 때 다시 재서 title 이 빠진다 — 다시 hover 해도 없다
    await input.fill(SHORT_NAME);
    await expect(input, '짧은 이름으로 고치면 title 이 빠진다').not.toHaveAttribute('title');
    await expectNoTitleOnHover(input);

    // 다시 긴 이름을 치면(hover 중) 새 이름으로 title 이 붙는다
    const renamed = `${LONG_UNBROKEN_NAME.slice(0, 99)}Y`;
    await input.fill(renamed);
    await expect(input).toHaveAttribute('title', renamed);
    await expectInsideHorizontally(input, header, '이름 입력칸', { content: false });
    for (const name of ['저장', '취소']) {
      const button = header.getByRole('button', { name, exact: true });
      await expect(button).toBeInViewport({ ratio: 1 });
      await expectInsideHorizontally(button, header, `「${name}」 버튼`);
    }
    await expectPageFits(page, SCENARIOS[0]);
  });
});

test.describe('TruncatedTitle 글자가 바뀌면 이전 title 을 뗀다 (WD-663)', () => {
  test('파이프라인 이름을 고치면 breadcrumb 마지막 항목의 예전 이름 title 이 남지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize(NARROW_DESKTOP_VIEWPORT);
    await grantPipelinePermissions(page);
    await SCENARIOS[0].setup(page, LONG_UNBROKEN_NAME);
    await page.goto('/pipelines/1');

    // hover 로 예전 이름 title 을 단다
    const current = page.getByRole('navigation', { name: '현재 위치' }).locator('[aria-current="page"]');
    await expectTruncatedWithHoverTitle(current, LONG_UNBROKEN_NAME);

    // 편집 모드에서 이름을 바꾸면 breadcrumb 글자도 바뀐다 — 다시 hover 하기 전까지 예전 이름 title 이 남으면 안 된다
    const header = page.locator('header');
    await header.getByRole('button', { name: '수정', exact: true }).click();
    const renamed = `${LONG_UNBROKEN_NAME.slice(0, 99)}Z`;
    await header.getByRole('textbox', { name: '파이프라인 이름' }).fill(renamed);
    await expect(current).toHaveText(renamed);
    await expect(current, '예전 이름 title 이 남지 않는다').not.toHaveAttribute('title');

    // 다시 hover 하면 새 이름으로 단다
    await expectTruncatedWithHoverTitle(current, renamed);
  });

  test('포인터가 위에 있는 채로 이름이 잘리는 길이로 바뀌면 바로 title 이 붙는다 (WD-669)', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize(NARROW_DESKTOP_VIEWPORT);
    await grantPipelinePermissions(page);
    await SCENARIOS[0].setup(page, SHORT_NAME);
    await page.goto('/pipelines/1');

    const header = page.locator('header');
    await header.getByRole('button', { name: '수정', exact: true }).click();
    const input = header.getByRole('textbox', { name: '파이프라인 이름' });
    await expect(input).toHaveValue(SHORT_NAME);

    // 짧은 이름 위에 포인터를 올려 둔다 — 잘리지 않았으니 title 이 없다
    const current = page.getByRole('navigation', { name: '현재 위치' }).locator('[aria-current="page"]');
    await expectNoTitleOnHover(current);

    // 포인터는 그대로 두고 키보드로 긴 이름을 친다 — pointerenter 가 다시 오지 않아도 새 글자로 다시 재서 title 을 단다
    await input.focus();
    await page.keyboard.press('ControlOrMeta+a');
    await page.keyboard.insertText(LONG_UNBROKEN_NAME);
    await expect(current).toHaveText(LONG_UNBROKEN_NAME);
    expect(await current.evaluate((el) => el.matches(':hover')), '포인터가 아직 위에 있어야 한다(전제)').toBe(true);
    await expect(current).toHaveAttribute('title', LONG_UNBROKEN_NAME);
  });
});
