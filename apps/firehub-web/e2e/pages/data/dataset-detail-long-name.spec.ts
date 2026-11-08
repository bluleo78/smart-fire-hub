import type { Locator, Page } from '@playwright/test';

import { createCategories, createCategory, createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';
import {
  expectInsideHorizontally,
  expectNoHorizontalOverflow,
  expectNoOverflowWithin,
  expectNoTitleOnHover,
  expectTruncatedWithHoverTitle,
  LONG_UNBROKEN_NAME,
  NARROW_DESKTOP_VIEWPORT,
} from '../../fixtures/long-unbroken-name';
import { expectValueNotTruncated } from '../../fixtures/table-layout';

/**
 * 데이터셋 상세 — 띄어쓰기 없는 긴 값의 배치 (WD-656)
 * - 예전에는 1024px 에서 라틴 문자 100자 이름이 제목(h1)·breadcrumb 마지막 항목·정보 카드 값 칸을 화면 밖으로 밀어냈다.
 * - 원칙(WD-517·WD-634): 긴 값은 줄바꿈하거나 말줄임 + title, 짧은 값은 잘리지 않고 툴팁도 없다.
 *   제목·테이블명·카드 값은 줄바꿈, breadcrumb 마지막 항목·태그 칩은 한 줄 말줄임 + (잘렸을 때만, hover 때 다는) title(WD-663).
 */

/** 긴 테이블명 — 실제 상한(63자)과 무관하게 넘침 조건을 만들려고 이름과 같은 길이로 둔다 */
const LONG_TABLE_NAME = LONG_UNBROKEN_NAME.toLowerCase();

/** 데이터셋 상세(id 1)를 주어진 필드로 띄울 준비를 한다 */
async function setupDataset(
  page: Page,
  fields: { name: string; categoryName?: string; tableName?: string; description?: string; tags?: string[] },
) {
  await setupDatasetDetailMocks(page, 1);
  await mockApi(page, 'GET', '/api/v1/dataset-categories', createCategories());
  await mockApi(page, 'GET', '/api/v1/datasets/tags', []);
  const { categoryName = '기본 카테고리', ...rest } = fields;
  await mockApi(
    page,
    'GET',
    '/api/v1/datasets/1',
    createDatasetDetail({ id: 1, ...rest, category: createCategory({ name: categoryName }) }),
  );
}

/** 정보 탭 「기본 정보」 카드와 라벨로 찾은 값 칸 */
function infoCard(page: Page) {
  return page.locator('[data-slot="card"]').filter({ has: page.getByRole('heading', { name: '기본 정보' }) });
}
function infoValue(page: Page, label: string) {
  return infoCard(page).locator('dl > div').filter({ has: page.locator('dt', { hasText: label }) }).locator('dd');
}
/** 요소가 몇 줄인지 — 높이를 줄 높이로 나눈다 */
function lineCount(selector: Locator) {
  return selector.evaluate((el) => Math.round(el.clientHeight / parseFloat(getComputedStyle(el).lineHeight)));
}
function breadcrumbCurrent(page: Page) {
  return page.getByRole('navigation', { name: '현재 위치' }).locator('[aria-current="page"]');
}
/**
 * breadcrumb 가운데 「데이터셋」 링크의 라벨을 긴 이름으로 바꾸고 그 링크를 돌려준다 (WD-663)
 * - 두 화면 모두 가운데 라벨이 고정 문구라 모킹으로 길게 만들 수 없다 — 글자 노드만 바꿔 배치(CSS)·title 동작을 검증한다.
 */
async function makeMiddleLabelLong(page: Page) {
  const nav = page.getByRole('navigation', { name: '현재 위치' });
  const middle = nav.getByRole('link', { name: '데이터셋', exact: true });
  await expect(middle).toBeVisible();
  await middle.evaluate((el, longLabel) => {
    const node = document.createTreeWalker(el, NodeFilter.SHOW_TEXT).nextNode();
    if (node) node.nodeValue = longLabel;
  }, LONG_UNBROKEN_NAME);
  const middleLink = nav.getByRole('link', { name: LONG_UNBROKEN_NAME });
  await expect(middleLink).toBeVisible();
  return middleLink;
}

test.describe('데이터셋 상세 긴 값 배치 (WD-656)', () => {
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await page.setViewportSize(NARROW_DESKTOP_VIEWPORT);
  });

  test('띄어쓰기 없는 100자 이름이 제목·breadcrumb·정보 카드 밖으로 넘치지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupDataset(page, { name: LONG_UNBROKEN_NAME, categoryName: LONG_UNBROKEN_NAME });
    await page.goto('/data/datasets/1');

    const h1 = page.getByRole('heading', { level: 1 });
    await expect(h1).toHaveText(LONG_UNBROKEN_NAME);
    await expect(infoValue(page, '이름')).toHaveText(LONG_UNBROKEN_NAME);
    await expectNoHorizontalOverflow(page);

    // 제목 — 본문 안에서 여러 줄로 줄바꿈되고, 머리글 동작 버튼은 그대로 보인다
    const main = page.locator('#main-content');
    await expectInsideHorizontally(h1, main, '제목(h1)');
    expect(await lineCount(h1), '긴 제목은 줄바꿈된다').toBeGreaterThan(1);
    await expect(page.getByRole('button', { name: '쿼리 작성' })).toBeInViewport();

    // breadcrumb 마지막 항목 — 한 줄 말줄임 + 전체 이름 title, 상위 항목과 같은 줄
    const nav = page.getByRole('navigation', { name: '현재 위치' });
    const current = breadcrumbCurrent(page);
    await expectTruncatedWithHoverTitle(current, LONG_UNBROKEN_NAME);
    expect(await lineCount(current)).toBe(1);
    const currentBox = await current.boundingBox();
    const navBox = await nav.boundingBox();
    expect(currentBox!.x + currentBox!.width, 'breadcrumb 항목 오른쪽 끝이 nav 밖').toBeLessThanOrEqual(
      navBox!.x + navBox!.width + 1,
    );
    const firstBox = await nav.getByRole('link', { name: '데이터', exact: true }).boundingBox();
    expect(Math.abs(firstBox!.y + firstBox!.height / 2 - (currentBox!.y + currentBox!.height / 2))).toBeLessThanOrEqual(2);

    // 정보 카드 값 칸 — 이름·카테고리가 카드 안에서 줄바꿈된다
    const card = infoCard(page);
    await expectInsideHorizontally(infoValue(page, '이름'), card, '정보 카드 이름 값');
    await expectInsideHorizontally(infoValue(page, '카테고리'), card, '정보 카드 카테고리 값');
  });

  test('정보 카드의 긴 테이블명·설명도 카드 안에서 줄바꿈된다', async ({ authenticatedPage: page }) => {
    const longDescription = `Desc_${'0123456789'.repeat(12)}`;
    await setupDataset(page, { name: '화재 출동 기록', tableName: LONG_TABLE_NAME, description: longDescription });
    await page.goto('/data/datasets/1');

    await expect(infoValue(page, '테이블명')).toHaveText(LONG_TABLE_NAME);
    await expect(infoValue(page, '설명')).toHaveText(longDescription);
    const card = infoCard(page);
    await expectInsideHorizontally(infoValue(page, '테이블명'), card, '정보 카드 테이블명 값');
    await expectInsideHorizontally(infoValue(page, '설명'), card, '정보 카드 설명 값');
    await expectNoHorizontalOverflow(page);
  });

  test('머리글의 긴 테이블명은 줄바꿈되고, 긴 태그는 칩 안에서 말줄임 + title 이며 제거 버튼이 보인다', async ({
    authenticatedPage: page,
  }) => {
    await grantDatasetPermissions(page);
    const longTag = `tag_${'abcdefghij'.repeat(10)}`;
    await setupDataset(page, { name: '화재 출동 기록', tableName: LONG_TABLE_NAME, tags: [longTag, '공식'] });
    await page.goto('/data/datasets/1');

    const main = page.locator('#main-content');
    // 머리글 「테이블: …」 줄 — 본문 안에서 줄바꿈된다
    const tableLine = main.locator('p', { hasText: `테이블: ${LONG_TABLE_NAME}` });
    await expect(tableLine).toBeVisible();
    await expectInsideHorizontally(tableLine, main, '머리글 테이블명');
    expect(await lineCount(tableLine), '긴 테이블명은 줄바꿈된다').toBeGreaterThan(1);

    // 긴 태그 — 칩이 본문 안에 있고, 글자는 말줄임 + title, 제거 버튼은 칩 안에서 보인다
    const removeLong = page.getByRole('button', { name: `${longTag} 태그 제거` });
    const longChip = removeLong.locator('xpath=..');
    await expectInsideHorizontally(longChip, main, '긴 태그 칩');
    await expectTruncatedWithHoverTitle(longChip.locator('span').first(), longTag);
    await expect(removeLong).toBeInViewport();
    await expectInsideHorizontally(removeLong, longChip, '긴 태그 제거 버튼');
    // 긴 태그 글자에 밀려 제거 버튼이 24×24px(WD-395, WCAG 2.5.8) 아래로 줄지 않는다
    const removeBox = await removeLong.boundingBox();
    expect(removeBox!.width, '긴 태그 제거 버튼 너비').toBeGreaterThanOrEqual(24);
    expect(removeBox!.height, '긴 태그 제거 버튼 높이').toBeGreaterThanOrEqual(24);

    // 짧은 태그 — 잘리지 않고 툴팁도 없다
    const shortText = page.getByRole('button', { name: '공식 태그 제거' }).locator('xpath=..').locator('span').first();
    await expect(shortText).toHaveText('공식');
    await expectNoTitleOnHover(shortText);
    await expectNoHorizontalOverflow(page);
  });

  test('짧은 이름은 잘리거나 줄바꿈되지 않고 breadcrumb 에 툴팁도 없다', async ({ authenticatedPage: page }) => {
    await setupDataset(page, { name: '화재 출동 기록' });
    await page.goto('/data/datasets/1');

    const h1 = page.getByRole('heading', { level: 1 });
    await expect(h1).toHaveText('화재 출동 기록');
    expect(await lineCount(h1)).toBe(1);

    const current = breadcrumbCurrent(page);
    await expect(current).toHaveText('화재 출동 기록');
    await expectNoTitleOnHover(current);

    await expect(infoValue(page, '카테고리')).toHaveText('기본 카테고리');
    await expectValueNotTruncated(infoValue(page, '카테고리'));
    await expectNoHorizontalOverflow(page);
  });
});

/**
 * 좁은 폭의 공용 Breadcrumb (WD-663)
 * - 예전 마지막 항목은 flex-1(basis 0)이라 줄을 바꾸지 않고 첫 줄 남은 폭만 받아, 한 줄이면 다 보일 이름도 몇 px + 「…」로 눌렸다.
 * - 가운데 항목은 줄어들지 않아 긴 상위 라벨이 화면 밖으로 넘쳤다.
 */
test.describe('좁은 폭 breadcrumb (WD-663)', () => {
  /** 가장 좁은 모바일 폭 — 「데이터 › 데이터셋 ›」 뒤에 남는 폭이 마지막 항목 기준 폭(10rem)보다 좁다 */
  const MOBILE_VIEWPORT = { width: 320, height: 800 } as const;
  /** 한 줄 전체 폭이면 다 보이지만, 「데이터 › 데이터셋 ›」 뒤 남는 폭에는 다 들어가지 않는 길이의 이름 */
  const MEDIUM_NAME = '화재 출동 기록 월별 통계 현황';

  test.beforeEach(async ({ authenticatedPage: page }) => {
    await page.setViewportSize(MOBILE_VIEWPORT);
  });

  test('320px 에서 한 줄에 들어가는 이름은 눌리지 않고(다음 줄로 내려가도 된다), 긴 이름은 화면 안에서 말줄임된다', async ({
    authenticatedPage: page,
  }) => {
    await setupDataset(page, { name: MEDIUM_NAME });
    await page.goto('/data/datasets/1');

    const nav = page.getByRole('navigation', { name: '현재 위치' });
    const current = breadcrumbCurrent(page);
    await expect(current).toHaveText(MEDIUM_NAME);
    // 전제 — 이름이 첫 줄 남는 폭(마지막 화살표 오른쪽 ~ nav 끝)보다 넓다. 예전에는 여기서 눌려 「…」가 됐다
    const fit = await nav.evaluate((n) => {
      const chevrons = n.querySelectorAll('li[aria-hidden]');
      const lastChevron = chevrons[chevrons.length - 1].getBoundingClientRect();
      const cur = n.querySelector('[aria-current]') as HTMLElement;
      return { text: cur.scrollWidth, remaining: n.getBoundingClientRect().right - lastChevron.right, full: n.clientWidth };
    });
    expect(fit.text, '이름이 첫 줄 남는 폭보다 넓어야 한다(전제)').toBeGreaterThan(fit.remaining);
    expect(fit.text, '이름이 한 줄 전체 폭에는 들어가야 한다(전제)').toBeLessThan(fit.full);
    await expectNoTitleOnHover(current);
    await expectInsideHorizontally(current, nav, 'breadcrumb 마지막 항목');
    await expectNoHorizontalOverflow(page);

    // 긴 이름 — 줄을 바꿔도 다 들어가지 않으므로 nav 폭 안에서 말줄임된다
    await setupDataset(page, { name: LONG_UNBROKEN_NAME });
    await page.reload();
    await expect(current).toHaveText(LONG_UNBROKEN_NAME);
    await expectTruncatedWithHoverTitle(current, LONG_UNBROKEN_NAME);
    await expectInsideHorizontally(current, nav, 'breadcrumb 마지막 항목(긴 이름)', { content: false });
    await expectNoHorizontalOverflow(page);
  });

  test('가운데 항목의 긴 라벨은 말줄임되고 페이지가 가로로 넘치지 않는다', async ({ authenticatedPage: page }) => {
    await setupDataset(page, { name: '화재 출동 기록' });
    await page.goto('/data/datasets/1');
    const nav = page.getByRole('navigation', { name: '현재 위치' });
    const middleLink = await makeMiddleLabelLong(page);

    // 가운데 항목이 nav·화면 밖으로 뻗지 않고, 마지막 항목도 화면 안에 남는다
    await expectInsideHorizontally(middleLink, nav, 'breadcrumb 가운데 항목', { content: false });
    await expectNoOverflowWithin(nav, 'breadcrumb');
    await expectNoHorizontalOverflow(page);
    await expect(breadcrumbCurrent(page)).toBeInViewport();
    // 라벨은 링크 안 TruncatedTitle 이 말줄임한다 — 링크는 그대로 링크다
    const label = middleLink.locator('span').first();
    expect(await label.evaluate((el) => el.scrollWidth > el.clientWidth), '긴 가운데 라벨은 말줄임된다').toBe(true);
    await expect(middleLink).toHaveAttribute('href', '/data/datasets');
  });

  test('키보드 Tab 으로 긴 가운데 링크에 포커스하면 라벨에 전체 이름 title 이 붙는다', async ({ authenticatedPage: page }) => {
    await setupDataset(page, { name: '화재 출동 기록' });
    await page.goto('/data/datasets/1');
    const nav = page.getByRole('navigation', { name: '현재 위치' });
    const middleLink = await makeMiddleLabelLong(page);
    const label = middleLink.locator('span').first();
    await expect(label).not.toHaveAttribute('title');

    // 앞 링크(「데이터」)에서 Tab 한 번 — 링크의 onFocus 가 안쪽 라벨의 잘림을 재서 title 을 넘겨 준다
    await page.mouse.move(0, 0);
    await nav.getByRole('link', { name: '데이터', exact: true }).focus();
    await page.keyboard.press('Tab');
    await expect(middleLink).toBeFocused();
    await expect(label).toHaveAttribute('title', LONG_UNBROKEN_NAME);
  });

  test('flex 가 아닌 블록 부모 안에서도 TruncatedTitle 은 말줄임된다', async ({ authenticatedPage: page }) => {
    await setupDataset(page, { name: LONG_UNBROKEN_NAME });
    await page.goto('/data/datasets/1');
    const current = breadcrumbCurrent(page);
    await expect(current).toHaveText(LONG_UNBROKEN_NAME);

    // 부모를 폭이 정해진 평범한 블록으로 바꾼다 — 인라인 span 이면 폭이 없어 말줄임이 걸리지 않는다
    await current.evaluate((el) => {
      const parent = el.parentElement!;
      parent.style.display = 'block';
      parent.style.width = '160px';
      parent.style.flex = 'none';
    });
    expect(await current.evaluate((el) => getComputedStyle(el).display)).toBe('block');
    const m = await current.evaluate((el) => ({ scroll: el.scrollWidth, client: el.clientWidth }));
    expect(m.client, '블록 부모 폭에 맞춰진다').toBeLessThanOrEqual(160);
    expect(m.scroll, '긴 이름은 말줄임된다').toBeGreaterThan(m.client);
  });
});
