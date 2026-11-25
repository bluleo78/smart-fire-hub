import type { Page } from '@playwright/test';

import { createCategory, createDataset } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions } from '../../fixtures/dataset.fixture';

/**
 * 데이터셋 목록 — 카테고리 칩 한 줄 접기 / 「+N 더 보기」 (WD-1073)
 *
 * 카테고리가 많거나 응답이 늦어도 칩 줄 높이가 한 줄(32px)로 고정돼 표가 밀리지 않아야 한다.
 */
const ONE_ROW_MAX = 40;

function makeCategories(n: number) {
  return Array.from({ length: n }, (_, i) => createCategory({ id: i + 1, name: `카테고리${i + 1}` }));
}

const datasets = Array.from({ length: 5 }, (_, i) =>
  createDataset({ id: i + 1, name: `데이터셋 ${i + 1}`, tags: [] }),
);

async function setup(page: Page, categoryCount: number, categoryDelayMs = 0) {
  await grantDatasetPermissions(page);
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  await page.route(
    (url) => url.pathname === '/api/v1/datasets',
    (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createPageResponse(datasets)),
      }),
  );
  await mockApi(page, 'GET', '/api/v1/dataset-categories', makeCategories(categoryCount), {
    delayMs: categoryDelayMs,
  });
}

/** 칩 묶음(role=group, 이름 「카테고리」) 로케이터. */
const groupOf = (page: Page) => page.getByRole('group', { name: '카테고리' });
const heightOf = async (page: Page) => (await groupOf(page).boundingBox())!.height;
const moreBtn = (page: Page) => page.getByRole('button', { name: /더 보기$/ });

/** 묶음 안에서 숨은(display:none) 칩 수. */
async function hiddenChipCount(page: Page) {
  // 칩마다 왕복하지 않고 한 번에 센다
  return groupOf(page)
    .locator('button[aria-pressed]')
    .evaluateAll((els) => els.filter((el) => getComputedStyle(el).display === 'none').length);
}

async function readN(page: Page) {
  const name = await moreBtn(page).innerText();
  const m = /^\+(\d+) 더 보기$/.exec(name.trim());
  expect(m, `버튼 문구: ${name}`).not.toBeNull();
  return Number(m![1]);
}

test.describe('데이터셋 목록 — 카테고리 칩 접기 (WD-1073)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
  });

  test('칩이 많으면 한 줄로 접히고 +N 더 보기로 펼친다', async ({ authenticatedPage: page }) => {
    await setup(page, 34);
    await page.goto('/data/datasets');
    await expect(page.getByRole('button', { name: '카테고리1', exact: true })).toBeVisible();

    expect(await heightOf(page)).toBeLessThanOrEqual(ONE_ROW_MAX);
    const btn = page.getByRole('button', { name: /^\+(\d+) 더 보기$/ });
    await expect(btn).toBeVisible();
    await expect(btn).toHaveAttribute('aria-expanded', 'false');
    const n = await readN(page);
    expect(n).toBeGreaterThan(0);
    expect(await hiddenChipCount(page)).toBe(n);

    await btn.click();
    const chips = groupOf(page).locator('button[aria-pressed]');
    await expect(chips).toHaveCount(35);
    await expect(groupOf(page).locator('button[aria-pressed]:visible')).toHaveCount(35);
    const fold = page.getByRole('button', { name: '접기', exact: true });
    await expect(fold).toHaveAttribute('aria-expanded', 'true');
    expect(await heightOf(page)).toBeGreaterThan(ONE_ROW_MAX);

    await fold.click();
    await expect(moreBtn(page)).toBeVisible();
    expect(await heightOf(page)).toBeLessThanOrEqual(ONE_ROW_MAX);
  });

  test('숨는 쪽 칩을 고르면 앞으로 와 보인다', async ({ authenticatedPage: page }) => {
    await setup(page, 34);
    await page.goto('/data/datasets?categoryId=34');
    const chip = page.getByRole('button', { name: '카테고리34', exact: true });
    await expect(chip).toBeVisible();
    await expect(chip).toHaveAttribute('aria-pressed', 'true');
    const all = page.getByRole('button', { name: '전체', exact: true });
    // 순서: 「전체」 바로 다음 칩이 고른 칩이다
    await expect(groupOf(page).locator('button[aria-pressed]:visible').nth(1)).toHaveText('카테고리34');
    const a = (await chip.boundingBox())!;
    const b = (await all.boundingBox())!;
    expect(Math.abs(a.y - b.y)).toBeLessThan(4);
  });

  test('창이 좁아지면 다시 재서 한 줄을 유지한다', async ({ authenticatedPage: page }) => {
    await setup(page, 34);
    await page.goto('/data/datasets');
    await expect(moreBtn(page)).toBeVisible();
    const before = await readN(page);

    await page.setViewportSize({ width: 900, height: 900 });
    await expect.poll(async () => (await moreBtn(page).isVisible() ? await readN(page) : 0)).toBeGreaterThan(before);
    expect(await heightOf(page)).toBeLessThanOrEqual(ONE_ROW_MAX);
  });

  test('칩이 한 줄에 들어가면 더 보기가 없다', async ({ authenticatedPage: page }) => {
    await setup(page, 3);
    await page.goto('/data/datasets');
    await expect(page.getByRole('button', { name: '카테고리3', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /더 보기/ })).toHaveCount(0);
  });

  test('카테고리 응답이 늦어도 표가 밀리지 않는다', async ({ authenticatedPage: page }) => {
    await setup(page, 34, 1000);
    await page.goto('/data/datasets');
    const table = page.getByRole('table');
    await expect(table).toBeVisible();
    const y0 = (await table.boundingBox())!.y;

    await expect(page.getByRole('button', { name: '카테고리1', exact: true })).toBeVisible();
    const y1 = (await table.boundingBox())!.y;
    expect(Math.abs(y1 - y0)).toBeLessThan(1);
  });

  test('펼친 채 칩을 고르면 펼침이 유지된다', async ({ authenticatedPage: page }) => {
    await setup(page, 34);
    await page.goto('/data/datasets');
    await moreBtn(page).click();
    await page.getByRole('button', { name: '카테고리30', exact: true }).click();
    await expect(page).toHaveURL(/categoryId=30/);
    await expect(page.getByRole('button', { name: '접기', exact: true })).toBeVisible();
  });

  test('접힌 상태에서 키보드로 칩에 초점을 줘도 포커스 링이 잘리지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 34);
    await page.goto('/data/datasets');
    await expect(moreBtn(page)).toBeVisible();
    const all = page.getByRole('button', { name: '전체', exact: true });
    await all.focus();
    // 첫 칩(전체, 왼쪽 가장자리)과 키보드로 옮긴 다음 칩 모두 링(3px)을 포함한 상자가 묶음 클립 상자 안이어야 한다
    for (const target of [all, page.getByRole('button', { name: '카테고리1', exact: true })]) {
      await target.focus();
      await page.keyboard.press('Shift+Tab');
      await page.keyboard.press('Tab');
      const g = (await groupOf(page).boundingBox())!;
      const c = (await target.boundingBox())!;
      expect(c.x - 3).toBeGreaterThanOrEqual(g.x);
      expect(c.y - 3).toBeGreaterThanOrEqual(g.y);
      expect(c.y + c.height + 3).toBeLessThanOrEqual(g.y + g.height);
      const shadow = await target.evaluate((el) => getComputedStyle(el).boxShadow);
      expect(shadow).not.toBe('none');
    }
  });

  test('칩이 넘쳐도 목록에 가로 스크롤이 생기지 않는다', async ({ authenticatedPage: page }) => {
    await setup(page, 34);
    for (const width of [1440, 320]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto('/data/datasets');
      await expect(moreBtn(page)).toBeVisible();
      const diff = await page.locator('main').evaluate((el) => el.scrollWidth - el.clientWidth);
      expect(diff, `width ${width}`).toBe(0);
    }
  });

  test('앞으로 당겨진 칩을 Enter 로 꺼도 초점이 body 로 떨어지지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 34);
    await page.goto('/data/datasets?categoryId=34');
    const chip = page.getByRole('button', { name: '카테고리34', exact: true });
    await expect(chip).toBeVisible();
    await chip.focus();
    await page.keyboard.press('Enter');
    await expect(page).not.toHaveURL(/categoryId=/);
    const inGroup = await page.evaluate(() => {
      const el = document.activeElement;
      return !!el && el !== document.body && !!el.closest('[role="group"][aria-label="카테고리"]');
    });
    expect(inGroup).toBe(true);
  });

  test('좁은 화면에서 이름이 긴 칩을 골라도 고른 칩과 +N 더 보기가 첫 줄에 보인다', async ({ authenticatedPage: page }) => {
    await setup(page, 34);
    // 34번 카테고리 이름을 아주 길게 바꿔 「전체」+고른 칩+버튼이 320px 한 줄에 다 들어가지 않게 한다(뒤에 건 route 가 먼저 받는다)
    const longName = '아주긴카테고리이름'.repeat(4);
    const cats = makeCategories(34).map((c) => (c.id === 34 ? { ...c, name: longName } : c));
    await mockApi(page, 'GET', '/api/v1/dataset-categories', cats);
    await page.setViewportSize({ width: 320, height: 900 });
    await page.goto('/data/datasets?categoryId=34');
    const chip = groupOf(page).getByRole('button', { name: longName, exact: true });
    await expect(chip).toBeVisible();
    await expect(chip).toHaveAttribute('aria-pressed', 'true');
    await expect(moreBtn(page)).toBeVisible();
    expect(await heightOf(page)).toBeLessThanOrEqual(ONE_ROW_MAX);
    // 고른 칩·버튼이 「전체」와 같은 줄이고 묶음 상자 안에 들어 있다(잘리지 않음)
    const g = (await groupOf(page).boundingBox())!;
    const all = (await groupOf(page).getByRole('button', { name: '전체', exact: true }).boundingBox())!;
    for (const box of [(await chip.boundingBox())!, (await moreBtn(page).boundingBox())!]) {
      expect(Math.abs(box.y - all.y)).toBeLessThan(4);
      expect(box.x + box.width).toBeLessThanOrEqual(g.x + g.width + 0.5);
    }
    // 펼치면 모든 칩에 닿는다
    await moreBtn(page).click();
    await expect(groupOf(page).getByRole('button', { name: '카테고리1', exact: true })).toBeVisible();
  });

  test('펼친 뒤 창이 넓어져 넘침이 없어지면 다시 좁힐 때 접힌 한 줄로 돌아온다', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 12);
    await page.setViewportSize({ width: 600, height: 900 });
    await page.goto('/data/datasets');
    await moreBtn(page).click();
    await expect(groupOf(page).getByRole('button', { name: '접기', exact: true })).toBeVisible();
    // 넓혀 모두 한 줄에 들어가면 버튼이 사라진다
    await page.setViewportSize({ width: 2560, height: 900 });
    await expect(groupOf(page).getByRole('button', { name: '접기', exact: true })).toHaveCount(0);
    await expect(moreBtn(page)).toHaveCount(0);
    // 다시 좁히면 펼친 채가 아니라 접힌 한 줄 + 「+N 더 보기」
    await page.setViewportSize({ width: 600, height: 900 });
    await expect(moreBtn(page)).toBeVisible();
    await expect(moreBtn(page)).toHaveAttribute('aria-expanded', 'false');
    expect(await heightOf(page)).toBeLessThanOrEqual(ONE_ROW_MAX);
  });
});
