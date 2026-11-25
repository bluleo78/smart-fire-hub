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
/** 「전체」 칩·「접기」 버튼 로케이터 — WD-1138 초점 유지 테스트에서 쓴다. */
const allBtn = (page: Page) => page.getByRole('button', { name: '전체', exact: true });
const foldBtn = (page: Page) => groupOf(page).getByRole('button', { name: '접기', exact: true });

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

  // WD-1138: 아주 좁은 칸(약 150px)에서도 「+N 더 보기」가 잘리지 않아야 한다 — 보이는 칩이 줄어들어 버튼 자리를 내준다.
  // WD-1151: 칸이 「전체」+고른 칩+「+N 더 보기」 전체 버튼을 못 담을 만큼 좁으면 버튼이 「+N」 으로 줄어 150px·120px 에서도 잘리지 않는다.
  async function narrowGroupTo(page: Page, px = 150) {
    await groupOf(page).evaluate((el, w) => {
      (el.parentElement as HTMLElement).style.width = `${w}px`;
    }, px);
  }
  async function expectMoreInsideGroup(page: Page) {
    // 좁힌 뒤 ResizeObserver 로 다시 재고 그릴 때까지 기다린다
    await expect
      .poll(async () => {
        const g = (await groupOf(page).boundingBox())!;
        const b = (await moreBtn(page).boundingBox())!;
        return b.x + b.width - (g.x + g.width);
      })
      .toBeLessThanOrEqual(0.5);
    const chips = groupOf(page).locator('button[aria-pressed]');
    const widths = await chips.evaluateAll((els) =>
      els.filter((el) => getComputedStyle(el).display !== 'none').map((el) => el.getBoundingClientRect().width),
    );
    for (const w of widths) expect(w).toBeGreaterThanOrEqual(32);
  }

  /** 짧은 「+N」 버튼 단언 — 글자는 정확히 +N, 접근 이름은 「+N 더 보기」, 칸 안에 들어간다. */
  async function expectCompactButton(page: Page) {
    await expect(moreBtn(page)).toHaveText(/^\+\d+$/);
    await expect(moreBtn(page)).toHaveAccessibleName(/^\+\d+ 더 보기$/);
    await expectMoreInsideGroup(page);
    // N 은 숨은 칩 수(전체 칩 수 - 보이는 칩 수)와 같다
    const text = (await moreBtn(page).innerText()).trim();
    const n = Number(/^\+(\d+)$/.exec(text)![1]);
    const total = await groupOf(page).locator('button[aria-pressed]').count();
    const hidden = await hiddenChipCount(page);
    expect(n).toBe(hidden);
    expect(total - hidden).toBeGreaterThanOrEqual(1);
  }

  /** 보이는 칩마다 「title 이 있음 ⇔ 이름이 실제로 잘림(scrollWidth > clientWidth)」 이어야 한다 — 매우 좁으면 「전체」도 잘려 title 이 달린다. */
  async function expectTitleOnlyWhenTruncated(page: Page) {
    const rows = await groupOf(page)
      .locator('button[aria-pressed]')
      .evaluateAll((els) =>
        els
          .filter((el) => getComputedStyle(el).display !== 'none')
          .map((el) => {
            const span = el.querySelector('span');
            // 제품 규칙(lib/overflow-title)과 같은 1px 허용으로 잘림을 판정한다
            return { truncated: !!span && span.scrollWidth - 1 > span.clientWidth, title: el.getAttribute('title'), text: el.textContent };
          }),
      );
    expect(rows.length).toBeGreaterThan(0);
    for (const r of rows) expect(r.title, `칩 ${r.text}`).toBe(r.truncated ? r.text : null);
  }

  test('아주 좁은 칸에서도 +N 더 보기가 잘리지 않는다 — 고른 칩 없음 (WD-1138)', async ({ authenticatedPage: page }) => {
    await setup(page, 34);
    await page.goto('/data/datasets');
    await expect(allBtn(page)).toBeVisible();
    await narrowGroupTo(page);
    await expect(moreBtn(page)).toBeVisible();
    await expectMoreInsideGroup(page);
  });

  test('120px 칸 + 고른 칩 없음이면 버튼이 「+N」 으로 줄어 잘리지 않고 눌린다 (WD-1151)', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 34);
    await page.goto('/data/datasets');
    await expect(allBtn(page)).toBeVisible();
    await narrowGroupTo(page, 120);
    await expect(moreBtn(page)).toBeVisible();
    await expectCompactButton(page);
    await moreBtn(page).click();
    await expect(foldBtn(page)).toHaveText('접기');
    await expect(foldBtn(page)).toHaveAttribute('aria-expanded', 'true');
  });

  test('아주 좁은 칸에서도 +N 더 보기가 잘리지 않는다 — 긴 이름 칩을 고른 상태 (WD-1138·WD-1151)', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 34);
    const longName = '아주긴카테고리이름'.repeat(4);
    const cats = makeCategories(34).map((c) => (c.id === 34 ? { ...c, name: longName } : c));
    await mockApi(page, 'GET', '/api/v1/dataset-categories', cats);
    await page.goto('/data/datasets?categoryId=34');
    await expect(groupOf(page).getByRole('button', { name: longName, exact: true })).toBeVisible();
    await narrowGroupTo(page, 150);
    await expect(moreBtn(page)).toBeVisible();
    await expectCompactButton(page);
    // 말줄임된 칩은 title 로 전체 이름을 보인다(「전체」는 달지 않는다).
    await expect(groupOf(page).getByRole('button', { name: longName, exact: true })).toHaveAttribute('title', longName);
    await expectTitleOnlyWhenTruncated(page);
    // 눌러 펼치면 「접기」 글자·aria-expanded=true
    await moreBtn(page).click();
    await expect(foldBtn(page)).toHaveText('접기');
    await expect(foldBtn(page)).toHaveAttribute('aria-expanded', 'true');
  });

  test('카테고리 하나를 고른 좁은 칸에서는 숨은 칩이 없어 「+0」 버튼이 없다 (WD-1151 code-review)', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 1);
    const longName = '아주긴카테고리이름'.repeat(4);
    await mockApi(page, 'GET', '/api/v1/dataset-categories', [createCategory({ id: 1, name: longName })]);
    await page.goto('/data/datasets?categoryId=1');
    const chip = groupOf(page).getByRole('button', { name: longName, exact: true });
    await expect(chip).toBeVisible();
    await narrowGroupTo(page, 150);
    // 다시 재고 그린 뒤: 고른 칩은 보이고(말줄임), 줄은 한 줄, 더 보기 버튼은 없다
    await expect(chip).toHaveAttribute('title', longName);
    await expect(chip).toBeVisible();
    await expect(moreBtn(page)).toHaveCount(0);
    expect(await heightOf(page)).toBeLessThanOrEqual(ONE_ROW_MAX);
  });

  test('루트 글자 크기가 커서 칩 최소 너비가 늘어도 더 보기 버튼이 잘리지 않는다 (WD-1151 code-review)', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 34);
    const longName = '아주긴카테고리이름'.repeat(4);
    const cats = makeCategories(34).map((c) => (c.id === 34 ? { ...c, name: longName } : c));
    await mockApi(page, 'GET', '/api/v1/dataset-categories', cats);
    await page.goto('/data/datasets?categoryId=34');
    // 브라우저 기본 글자 크기 20px 처럼 루트를 키운다 — min-w-8(2rem)은 40px 가 된다.
    // 회귀 지킴이: 고정 32px 로 계산하던 옛 코드도 이 폭들에서는 통과한다(경계 폭이 좁아 판별 폭을 고정하기 어렵다).
    await page.evaluate(() => {
      document.documentElement.style.fontSize = '20px';
    });
    await expect(groupOf(page).getByRole('button', { name: longName, exact: true })).toBeVisible();
    for (const px of [170, 200, 230]) {
      await narrowGroupTo(page, px);
      await expect(moreBtn(page)).toBeVisible();
      await expectMoreInsideGroup(page);
    }
  });

  test('중간 폭(320px)에서 긴 이름 칩을 골라도 칩이 말줄임되고 버튼은 「+N 더 보기」 전체 글자다 (WD-1151)', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 34);
    const longName = '아주긴카테고리이름'.repeat(4);
    const cats = makeCategories(34).map((c) => (c.id === 34 ? { ...c, name: longName } : c));
    await mockApi(page, 'GET', '/api/v1/dataset-categories', cats);
    await page.setViewportSize({ width: 320, height: 900 });
    await page.goto('/data/datasets?categoryId=34');
    const chip = groupOf(page).getByRole('button', { name: longName, exact: true });
    await expect(chip).toBeVisible();
    await expect(moreBtn(page)).toBeVisible();
    await expect(moreBtn(page)).toHaveText(/^\+\d+ 더 보기$/);
    await expectMoreInsideGroup(page);
    // 고른 칩은 보이되 이름이 말줄임(잘림)된다
    const truncated = await chip.locator('span').evaluate((el) => el.scrollWidth > el.clientWidth);
    expect(truncated).toBe(true);
  });

  test('150px 에서 짧아진 버튼은 칸을 넓히면 「+N 더 보기」 전체 글자로 돌아온다 (WD-1151)', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 34);
    const longName = '아주긴카테고리이름'.repeat(4);
    const cats = makeCategories(34).map((c) => (c.id === 34 ? { ...c, name: longName } : c));
    await mockApi(page, 'GET', '/api/v1/dataset-categories', cats);
    await page.goto('/data/datasets?categoryId=34');
    await expect(groupOf(page).getByRole('button', { name: longName, exact: true })).toBeVisible();
    await narrowGroupTo(page, 150);
    await expectCompactButton(page);
    await narrowGroupTo(page, 600);
    await expect(moreBtn(page)).toHaveText(/^\+\d+ 더 보기$/);
    await expectMoreInsideGroup(page);
  });

  test('칸 폭이 0 이어도(측정된 갈래) 「전체」와 고른 칩이 보인다 (WD-1138)', async ({ authenticatedPage: page }) => {
    // 범위: 측정은 있지만 칸 폭이 0 인 경우 — 측정된 갈래의 「전체」+고른 칩 최소 2개(max(2, …))를 지킨다.
    // 측정 전 갈래(측정값 없음)는 레이아웃 이펙트가 칠하기 전에 끝내 E2E 로 관찰할 수 없어 이 시험이 닿지 않는다.
    await page.addInitScript(() => {
      const desc = Object.getOwnPropertyDescriptor(Element.prototype, 'clientWidth')!;
      Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
        configurable: true,
        get() {
          return this.getAttribute('aria-label') === '카테고리' ? 0 : desc.get!.call(this);
        },
      });
    });
    await setup(page, 34);
    await page.goto('/data/datasets?categoryId=34');
    await expect(allBtn(page)).toBeVisible();
    const chip34 = groupOf(page).getByRole('button', { name: '카테고리34', exact: true });
    await expect(chip34).toBeVisible();
    await expect(chip34).toHaveAttribute('aria-pressed', 'true');
  });

  test('더 보기 버튼의 aria-controls 가 칩 묶음 id 와 같다 (WD-1138)', async ({ authenticatedPage: page }) => {
    await setup(page, 34);
    await page.goto('/data/datasets');
    await expect(moreBtn(page)).toBeVisible();
    const groupId = await groupOf(page).getAttribute('id');
    expect(groupId).toBeTruthy();
    await expect(moreBtn(page)).toHaveAttribute('aria-controls', groupId!);
  });

  test('칩을 고르면 목록 요청에 categoryId 가 실리고 「전체」는 빠진다 (WD-1138)', async ({ authenticatedPage: page }) => {
    await setup(page, 5);
    const isList = (r: { url(): string }, id: string | null) => {
      const u = new URL(r.url());
      return u.pathname === '/api/v1/datasets' && u.searchParams.get('categoryId') === id;
    };
    // 처음부터 분류 3 으로 진입해 같은 질의 캐시에 가려지지 않게 한 뒤, 다른 칩(4)과 「전체」 요청을 각각 새로 관찰한다
    const initial = page.waitForRequest((r) => isList(r, '3'));
    await page.goto('/data/datasets?categoryId=3');
    await initial;
    const with4 = page.waitForRequest((r) => isList(r, '4'));
    await groupOf(page).getByRole('button', { name: '카테고리4', exact: true }).click();
    await with4;
    const without = page.waitForRequest((r) => isList(r, null));
    await allBtn(page).click();
    await without;
  });

  // WD-1138: 초점을 가진 요소가 창 크기 변화로 사라지거나 숨으면 초점이 body 로 떨어지지 않고 「전체」로 가야 한다.

  test('펼친 채 「접기」에 초점이 있을 때 창이 넓어져 버튼이 사라지면 초점이 「전체」로 간다 (WD-1138)', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 12);
    await page.setViewportSize({ width: 600, height: 900 });
    await page.goto('/data/datasets');
    await moreBtn(page).click();
    await foldBtn(page).focus();
    await expect(foldBtn(page)).toBeFocused();
    await page.setViewportSize({ width: 2560, height: 900 });
    await expect(foldBtn(page)).toHaveCount(0);
    await expect(allBtn(page)).toBeFocused();
  });

  test('초점을 「전체」로 옮겨도 스크롤 위치는 그대로다 (WD-1138)', async ({ authenticatedPage: page }) => {
    // 낮은 창에서 「접기」에 초점을 둔 채 아래로 스크롤하고 넓히면, 초점은 「전체」로 가되 화면이 칩 줄로 튀지 않아야 한다(preventScroll).
    await setup(page, 12);
    await page.setViewportSize({ width: 600, height: 260 });
    await page.goto('/data/datasets');
    await moreBtn(page).click();
    await foldBtn(page).focus();
    // 칩 묶음을 감싼 스크롤 상자(없으면 문서)의 scrollTop 을 읽는다 — toBottom 이면 먼저 맨 아래로 내린다
    const scrollerTop = (toBottom = false) =>
      groupOf(page).evaluate((el, bottom) => {
        let n: HTMLElement | null = el.parentElement;
        while (n && !(n.scrollHeight > n.clientHeight && /auto|scroll/.test(getComputedStyle(n).overflowY))) n = n.parentElement;
        const s = n ?? (document.scrollingElement as HTMLElement);
        if (bottom) s.scrollTop = s.scrollHeight;
        return s.scrollTop;
      }, toBottom);
    await scrollerTop(true);
    expect(await scrollerTop()).toBeGreaterThan(0);
    // 칩 줄이 화면 위로 스크롤돼 나가 있는지 확인해 둔다
    expect((await groupOf(page).boundingBox())!.y).toBeLessThan(0);
    await page.setViewportSize({ width: 2560, height: 260 });
    await expect(foldBtn(page)).toHaveCount(0);
    await expect(allBtn(page)).toBeFocused();
    // 넓히면 내용 높이가 줄어 스크롤 값은 달라질 수 있지만, focus() 가 칩 줄을 화면으로 끌어오지는 않아야 한다
    expect(await scrollerTop()).toBeGreaterThan(0);
    expect((await groupOf(page).boundingBox())!.y).toBeLessThan(0);
  });

  test('접힌 채 끝 칩에 초점이 있을 때 창이 좁아져 칩이 숨으면 초점이 「전체」로 간다 (WD-1138)', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 12);
    await page.setViewportSize({ width: 2560, height: 900 });
    await page.goto('/data/datasets');
    const last = page.getByRole('button', { name: '카테고리12', exact: true });
    await last.focus();
    await expect(last).toBeFocused();
    await page.setViewportSize({ width: 600, height: 900 });
    await expect(moreBtn(page)).toBeVisible();
    await expect(last).toBeHidden();
    await expect(allBtn(page)).toBeFocused();
  });

  test('묶음 밖으로 초점을 옮기거나 빈 곳을 눌러 뺀 뒤 창을 바꾸면 초점을 끌어오지 않는다 (WD-1138)', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 12);
    await page.setViewportSize({ width: 600, height: 900 });
    await page.goto('/data/datasets');
    await moreBtn(page).click();
    await foldBtn(page).focus();
    // 묶음 밖(탭 이동)
    await page.keyboard.press('Tab');
    // 같은 요소인지 신원으로 비교하려고 지금 활성 요소에 표식을 단다(tagName 만으로는 다른 요소와 구분되지 않는다)
    const outside = await page.evaluate(() => {
      document.activeElement?.setAttribute('data-wd1138-outside', '1');
      return document.activeElement?.tagName;
    });
    expect(outside).not.toBe('BODY');
    await expect(groupOf(page).locator(':focus')).toHaveCount(0);
    await page.setViewportSize({ width: 2560, height: 900 });
    await expect(foldBtn(page)).toHaveCount(0);
    await expect(allBtn(page)).not.toBeFocused();
    expect(await page.evaluate(() => document.activeElement?.getAttribute('data-wd1138-outside'))).toBe('1');

    // 마우스로 빈 곳을 눌러 body 로 뺀 경우
    await page.setViewportSize({ width: 600, height: 900 });
    await moreBtn(page).click();
    await foldBtn(page).focus();
    await page.evaluate(() => (document.activeElement as HTMLElement).blur());
    await page.setViewportSize({ width: 2560, height: 900 });
    await expect(foldBtn(page)).toHaveCount(0);
    await expect(allBtn(page)).not.toBeFocused();
  });
  // WD-1151: 카테고리 수가 바뀐 직후의 임시 렌더(측정 없음)에서는 초점을 판단하지 않는다 — 회귀 방지용(null 측정 갈래).
  // 단독으로는 변경 전에도 통과한다(수가 다르면 측정이 null 이라 이펙트가 일찍 돌아온다). 같은 수 갈래는 아래 테스트가 가른다.
  test('접힌 채 앞쪽 칩에 초점이 있을 때 카테고리가 하나 늘어도 초점이 그 칩에 남는다 (WD-1151)', async ({
    authenticatedPage: page,
  }) => {
    // 쿼리 staleTime(30초)을 넘겨 재연결(online) 때 목록이 다시 조회되게 가짜 시계를 쓴다
    await page.clock.install();
    await setup(page, 12);
    await page.setViewportSize({ width: 600, height: 900 });
    await page.goto('/data/datasets');
    await expect(moreBtn(page)).toBeVisible();
    const chip = groupOf(page).getByRole('button', { name: '카테고리2', exact: true });
    await chip.focus();
    await expect(chip).toBeFocused();
    await mockApi(page, 'GET', '/api/v1/dataset-categories', makeCategories(13));
    await page.clock.fastForward(31_000);
    await page.context().setOffline(true);
    await page.context().setOffline(false);
    await expect(groupOf(page).locator('button[aria-pressed]')).toHaveCount(14);
    await expect(chip).toBeFocused();
    await expect(allBtn(page)).not.toBeFocused();
  });

  // WD-1151 최종 리뷰: 같은 수로 이름·순서만 바뀐 응답이 오면 낡은 측정(너비)이 길이가 맞아 null 이 되지 않는다.
  // 낡은 너비로 계산한 임시 렌더가 초점 칩을 숨기면 이펙트가 초점을 「전체」로 옮겨 버렸다 — 측정은 items 신원으로 낡음을 판단한다.
  test('같은 수로 이름·순서가 바뀐 응답이 와도 초점 칩이 새 측정에서 보이면 초점이 그 칩에 남는다 (WD-1151)', async ({
    authenticatedPage: page,
  }) => {
    await page.clock.install();
    await setup(page, 12);
    // 처음: 이름이 긴 12개 — 한 줄에 다 못 들어가 접힌다. 초점 칩(대상칩)은 둘째 자리라 보인다.
    const before = Array.from({ length: 12 }, (_, i) =>
      createCategory({ id: i + 1, name: i === 1 ? '대상칩' : `아주긴카테고리이름${i + 1}` }),
    );
    await mockApi(page, 'GET', '/api/v1/dataset-categories', before);
    await page.setViewportSize({ width: 1200, height: 900 });
    await page.goto('/data/datasets');
    await expect(moreBtn(page)).toBeVisible();
    const chip = groupOf(page).getByRole('button', { name: '대상칩', exact: true });
    await expect(chip).toBeVisible();
    await chip.focus();
    await expect(chip).toBeFocused();
    // 같은 12개이지만 대상칩 앞에 이름이 짧은 새 카테고리 10개가 들어와 대상칩이 11번째 자리로 밀린다 —
    // 낡은 너비로는 숨고, 새로 재면 짧은 이름이라 전부 한 줄에 들어가 보인다. (대상칩 DOM 노드는 옮기지 않는다 —
    // 옮기면 브라우저가 초점을 잃어 이 테스트가 가리려는 갈래와 무관하게 실패한다.)
    const after = [
      ...Array.from({ length: 10 }, (_, i) => createCategory({ id: 101 + i, name: `c${i + 1}` })),
      before[1],
      createCategory({ id: 12, name: 'c11' }),
    ];
    await mockApi(page, 'GET', '/api/v1/dataset-categories', after);
    await page.clock.fastForward(31_000);
    await page.context().setOffline(true);
    await page.context().setOffline(false);
    // 새 응답이 반영돼 더 보기가 사라지고(전부 보임) 대상칩이 맨 뒤에 놓일 때까지 기다린다
    await expect(groupOf(page).getByRole('button', { name: 'c1', exact: true })).toBeVisible();
    await expect(moreBtn(page)).toHaveCount(0);
    await expect(chip).toBeVisible();
    await expect(chip).toBeFocused();
    await expect(allBtn(page)).not.toBeFocused();
  });

  test('넓은 칸에서는 칩 button 에 title 이 없다 (WD-1151)', async ({ authenticatedPage: page }) => {
    await setup(page, 3);
    await page.goto('/data/datasets');
    await expect(page.getByRole('button', { name: '카테고리3', exact: true })).toBeVisible();
    const titled = await groupOf(page)
      .locator('button[aria-pressed]')
      .evaluateAll((els) => els.filter((el) => el.hasAttribute('title')).length);
    expect(titled).toBe(0);
  });

  test('title 은 말줄임된 칩에만 달리고 잘리지 않은 보이는 칩에는 없다 (WD-1151)', async ({
    authenticatedPage: page,
  }) => {
    // 600px 접힘 — 보이는 칩이 줄어들 수 있어도(shrinkable) 잘리지 않았으면 title 이 없어야 한다
    await setup(page, 12);
    await page.setViewportSize({ width: 600, height: 900 });
    await page.goto('/data/datasets');
    await expect(moreBtn(page)).toBeVisible();
    const titled = await groupOf(page)
      .locator('button[aria-pressed]')
      .evaluateAll((els) => els.filter((el) => el.hasAttribute('title')).length);
    expect(titled).toBe(0);
  });

  test('320px 에서 말줄임된 고른 칩에는 title=이름이 달리고, 칸을 넓혀 잘림이 풀리면 사라진다 (WD-1151)', async ({
    authenticatedPage: page,
  }) => {
    await setup(page, 34);
    const longName = '아주긴카테고리이름'.repeat(4);
    const cats = makeCategories(34).map((c) => (c.id === 34 ? { ...c, name: longName } : c));
    await mockApi(page, 'GET', '/api/v1/dataset-categories', cats);
    await page.setViewportSize({ width: 320, height: 900 });
    await page.goto('/data/datasets?categoryId=34');
    const chip = groupOf(page).getByRole('button', { name: longName, exact: true });
    await expect(chip).toBeVisible();
    await expect(chip).toHaveAttribute('title', longName);
    await expectTitleOnlyWhenTruncated(page);
    // 칸을 넓혀 말줄임이 풀리면 title 도 사라진다
    await page.setViewportSize({ width: 1440, height: 900 });
    await expect(chip).not.toHaveAttribute('title', /.*/);
  });
});
