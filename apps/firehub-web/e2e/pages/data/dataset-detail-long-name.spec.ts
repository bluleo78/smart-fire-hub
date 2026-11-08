import type { Locator, Page } from '@playwright/test';

import { createCategories, createCategory, createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantDatasetPermissions, setupDatasetDetailMocks } from '../../fixtures/dataset.fixture';
import {
  expectInsideHorizontally,
  expectNoHorizontalOverflow,
  LONG_UNBROKEN_NAME,
  NARROW_DESKTOP_VIEWPORT,
} from '../../fixtures/long-unbroken-name';
import { expectLongValueTruncated, expectValueNotTruncated } from '../../fixtures/table-layout';

/**
 * 데이터셋 상세 — 띄어쓰기 없는 긴 값의 배치 (WD-656)
 * - 예전에는 1024px 에서 라틴 문자 100자 이름이 제목(h1)·breadcrumb 마지막 항목·정보 카드 값 칸을 화면 밖으로 밀어냈다.
 * - 원칙(WD-517·WD-634): 긴 값은 줄바꿈하거나 말줄임 + title, 짧은 값은 잘리지 않고 툴팁도 없다.
 *   제목·테이블명·카드 값은 줄바꿈, breadcrumb 마지막 항목·태그 칩은 한 줄 말줄임 + (잘렸을 때만) title.
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
    await expectLongValueTruncated(current, LONG_UNBROKEN_NAME);
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
    await expectLongValueTruncated(longChip.locator('span').first(), longTag);
    await expect(removeLong).toBeInViewport();
    await expectInsideHorizontally(removeLong, longChip, '긴 태그 제거 버튼');

    // 짧은 태그 — 잘리지 않고 툴팁도 없다
    const shortText = page.getByRole('button', { name: '공식 태그 제거' }).locator('xpath=..').locator('span').first();
    await expect(shortText).toHaveText('공식');
    await expectValueNotTruncated(shortText);
    await expect(shortText).not.toHaveAttribute('title');
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
    await expectValueNotTruncated(current);
    await expect(current).not.toHaveAttribute('title');

    await expect(infoValue(page, '카테고리')).toHaveText('기본 카테고리');
    await expectValueNotTruncated(infoValue(page, '카테고리'));
    await expectNoHorizontalOverflow(page);
  });
});
