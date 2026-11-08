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
}

const SCENARIOS: Scenario[] = [
  {
    label: '파이프라인',
    path: '/pipelines/1',
    canvas: true,
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
      await expectLongValueTruncated(title, LONG_UNBROKEN_NAME);

      // 공용 Breadcrumb 를 쓰는 화면(파이프라인)은 마지막 항목도 말줄임 + title 이다
      const nav = page.getByRole('navigation', { name: '현재 위치' });
      if ((await nav.count()) > 0) {
        const current = nav.locator('[aria-current="page"]');
        await expectLongValueTruncated(current, LONG_UNBROKEN_NAME);
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
      await expectValueNotTruncated(title);
      const nav = page.getByRole('navigation', { name: '현재 위치' });
      if ((await nav.count()) > 0) {
        const current = nav.locator('[aria-current="page"]');
        await expectValueNotTruncated(current);
        await expect(current, '잘리지 않은 breadcrumb 항목에는 툴팁이 없다').not.toHaveAttribute('title');
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

  test('긴 이름이어도 머리글 동작 버튼이 화면·머리글 안에 보이고, 편집 모드 입력칸은 전체 이름 title 을 가진다', async ({
    authenticatedPage: page,
  }) => {
    const header = page.locator('header');
    // 조회 모드 — 실행·수정 버튼이 머리글 밖으로 밀려 잘리지 않는다
    for (const name of ['실행', '수정']) {
      const button = header.getByRole('button', { name, exact: true });
      await expect(button).toBeInViewport({ ratio: 1 });
      await expectInsideHorizontally(button, header, `「${name}」 버튼`);
    }

    // 편집 모드 — 이름 입력칸은 400px 에서 잘리므로 title 로 전체 이름을 보이고, 저장·취소 버튼도 보인다
    await header.getByRole('button', { name: '수정', exact: true }).click();
    const input = header.getByRole('textbox', { name: '파이프라인 이름' });
    await expect(input).toHaveValue(LONG_UNBROKEN_NAME);
    await expect(input).toHaveAttribute('title', LONG_UNBROKEN_NAME);
    await expectInsideHorizontally(input, header, '이름 입력칸', { content: false });
    for (const name of ['저장', '취소']) {
      const button = header.getByRole('button', { name, exact: true });
      await expect(button).toBeInViewport({ ratio: 1 });
      await expectInsideHorizontally(button, header, `「${name}」 버튼`);
    }
    await expectPageFits(page, SCENARIOS[0]);
  });
});
