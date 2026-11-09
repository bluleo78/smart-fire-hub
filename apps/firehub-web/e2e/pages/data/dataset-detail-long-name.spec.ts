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
 * breadcrumb 가운데 「데이터셋」 링크의 라벨이 12rem 을 넘게 넓어지게 하고 그 링크와 라벨을 돌려준다 (WD-663, WD-669)
 * - 두 화면 모두 가운데 라벨이 고정 문구라 모킹으로 길게 만들 수 없다.
 * - 예전에는 React 가 관리하는 글자 노드의 nodeValue 를 직접 바꿨는데, 다시 그리면 원래 라벨로 돌아가 테스트가 흔들릴 수 있었다(WD-669).
 *   대신 스타일시트(letter-spacing)로 라벨 폭만 넓힌다 — React 가 다시 그려도 그대로 남고, 배치(CSS)·title 동작은 같은 조건으로 검증된다.
 */
async function makeMiddleLabelWide(page: Page) {
  const nav = page.getByRole('navigation', { name: '현재 위치' });
  const middleLink = nav.getByRole('link', { name: '데이터셋', exact: true });
  await expect(middleLink).toBeVisible();
  // 「데이터 › 데이터셋 › 이름」 의 세 번째 li(가운데 항목) 라벨 — 글자 사이를 4rem 씩 벌려 12rem(192px)을 넉넉히 넘긴다
  await page.addStyleTag({
    content: 'nav[aria-label="현재 위치"] ol > li:nth-child(3) a > span { letter-spacing: 4rem; }',
  });
  const label = middleLink.locator('span').first();
  await expect
    .poll(() => label.evaluate((el) => el.scrollWidth), { message: '가운데 라벨이 12rem 보다 넓어져야 한다(전제)' })
    .toBeGreaterThan(192);
  return { middleLink, label };
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
      // 글자 폭은 Range 로 잰다 — 마지막 항목은 칸 폭을 채우므로(WD-669) scrollWidth 는 글자가 아니라 칸 폭이다
      const range = document.createRange();
      range.selectNodeContents(cur);
      const text = range.getBoundingClientRect().width;
      return { text, remaining: n.getBoundingClientRect().right - lastChevron.right, full: n.clientWidth };
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
    const { middleLink, label } = await makeMiddleLabelWide(page);

    // 가운데 항목이 nav·화면 밖으로 뻗지 않고, 마지막 항목도 화면 안에 남는다
    await expectInsideHorizontally(middleLink, nav, 'breadcrumb 가운데 항목', { content: false });
    await expectNoOverflowWithin(nav, 'breadcrumb');
    await expectNoHorizontalOverflow(page);
    await expect(breadcrumbCurrent(page)).toBeInViewport();
    // 라벨은 링크 안 TruncatedTitle 이 말줄임하고 hover 하면 전체 라벨 title 이 붙는다 — 링크는 그대로 링크다
    await expectTruncatedWithHoverTitle(label, '데이터셋');
    await expect(middleLink).toHaveAttribute('href', '/data/datasets');
  });

  test('320px 에서 첫 줄에 들어가는 짧은 이름은 다음 줄로 밀리지 않고 한 줄에 남는다 (WD-669)', async ({
    authenticatedPage: page,
  }) => {
    // 「화재 출동 기록」(~90px) — 첫 줄 남는 폭은 10rem(160px)보다 좁지만 이름은 들어간다.
    // 예전 flex-[1_1_10rem] 은 기준 폭 160px 로 재서 이 이름도 둘째 줄로 밀었다.
    await setupDataset(page, { name: '화재 출동 기록' });
    await page.goto('/data/datasets/1');

    const nav = page.getByRole('navigation', { name: '현재 위치' });
    const current = breadcrumbCurrent(page);
    await expect(current).toHaveText('화재 출동 기록');
    // 전제 — 첫 줄 남는 폭이 10rem 보다 좁고, 이름은 그 안에 들어간다
    const fit = await nav.evaluate((n) => {
      const chevrons = n.querySelectorAll('li[aria-hidden]');
      const lastChevron = chevrons[chevrons.length - 1].getBoundingClientRect();
      const cur = n.querySelector('[aria-current]') as HTMLElement;
      const rootFontSize = parseFloat(getComputedStyle(document.documentElement).fontSize);
      // 글자 폭은 Range 로 잰다 — 마지막 항목은 칸 폭을 채우므로 scrollWidth 는 글자가 아니라 칸 폭이다
      const range = document.createRange();
      range.selectNodeContents(cur);
      return { text: range.getBoundingClientRect().width, remaining: n.getBoundingClientRect().right - lastChevron.right, basis: 10 * rootFontSize };
    });
    expect(fit.remaining, '첫 줄 남는 폭이 10rem 보다 좁아야 한다(전제)').toBeLessThan(fit.basis);
    expect(fit.text, '이름은 첫 줄 남는 폭에 들어가야 한다(전제)').toBeLessThan(fit.remaining);

    // 한 줄 — 마지막 항목이 첫 링크와 같은 줄이고, breadcrumb 목록 높이가 한 줄 높이다
    const firstBox = await nav.getByRole('link', { name: '데이터', exact: true }).boundingBox();
    const currentBox = await current.boundingBox();
    expect(
      Math.abs(firstBox!.y + firstBox!.height / 2 - (currentBox!.y + currentBox!.height / 2)),
      '마지막 항목이 첫 링크와 같은 줄',
    ).toBeLessThanOrEqual(2);
    const list = await nav.locator('ol').evaluate((ol) => ({
      height: ol.getBoundingClientRect().height,
      lineHeight: parseFloat(getComputedStyle(ol).lineHeight),
    }));
    expect(list.height, 'breadcrumb 가 한 줄 높이').toBeLessThan(list.lineHeight * 1.5);
    expect(await lineCount(current)).toBe(1);
    await expectNoTitleOnHover(current);
    await expectNoHorizontalOverflow(page);
  });

  test('1px 이내로 넘치는 글자(반올림 차이)에는 title 을 달지 않는다 (WD-669)', async ({ authenticatedPage: page }) => {
    await setupDataset(page, { name: '화재 출동 기록' });
    await page.goto('/data/datasets/1');
    const current = breadcrumbCurrent(page);
    await expect(current).toHaveText('화재 출동 기록');

    // 글자 폭 근처에서 폭을 줄여 가며 scrollWidth 가 clientWidth 보다 정확히 1 큰 폭을 찾는다 — 소수점 글자 폭의 반올림 차이를 재현한다.
    // 마지막 항목은 칸 폭을 채우므로 글자 폭은 scrollWidth 가 아니라 Range 로 잰다.
    const diff = await current.evaluate((el) => {
      const range = document.createRange();
      range.selectNodeContents(el);
      const natural = Math.ceil(range.getBoundingClientRect().width) + 1;
      for (let w = natural; w >= natural - 4; w--) {
        el.style.width = `${w}px`;
        if (el.scrollWidth - el.clientWidth === 1) return 1;
      }
      return el.scrollWidth - el.clientWidth;
    });
    expect(diff, '1px 넘치는 폭을 만들 수 있어야 한다(전제)').toBe(1);
    await page.mouse.move(0, 0);
    await current.hover();
    await expect(current, '1px 이내 넘침은 잘리지 않은 것으로 본다').not.toHaveAttribute('title');

    // 2px 이상 넘치면(실제로 잘림) title 이 붙는다 — 여유가 판정을 아예 꺼 버리지 않았는지 함께 본다
    await current.evaluate((el) => {
      el.style.width = `${el.clientWidth - 8}px`;
    });
    await page.mouse.move(0, 0);
    await current.hover();
    await expect(current).toHaveAttribute('title', '화재 출동 기록');
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
