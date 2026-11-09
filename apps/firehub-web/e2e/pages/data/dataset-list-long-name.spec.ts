import type { Page } from '@playwright/test';

import type { DatasetResponse } from '@/types/dataset';

import { createDataset, createDatasets } from '../../factories/dataset.factory';
import { createLevelSummary } from '../../factories/security-level.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';
import {
  expectBadgesFitDeclaredWidth,
  expectCellWithinLongValueCap,
  expectKMatchesDeclaredWidths,
  expectLongValueTruncated,
  expectNoTitle,
  expectTableFits,
  expectValueNotTruncated,
  measureCellInnerWidth,
  TABLE_LAYOUT_WIDTHS,
} from '../../fixtures/table-layout';

/**
 * 데이터셋 목록 — 띄어쓰기 없는 긴 이름·태그·카테고리 (WD-517, WD-634)
 * - 표 셀은 줄바꿈하지 않아(whitespace-nowrap) 100자 이름이 표를 가로로 밀어 보안 등급·태그·생성일 칸이 화면 밖으로 나갔다.
 * - 1280·1024 두 폭에서 표가 들어맞고 마지막 칸의 오른쪽 끝이 컨테이너 안이며, 긴 값은 말줄임되고 전체 값은 title 로 남는다.
 */
test.describe('데이터셋 목록 긴 이름 말줄임 (WD-517, WD-634)', () => {
  const LONG_NAME = '가'.repeat(100);
  const NAME = '119 출동 기록 원천 데이터 2025';
  // 공백 없는 긴 태그 여럿·긴 카테고리 이름 — 태그·카테고리 칸도 선언 폭을 넘지 않아야 한다
  const LONG_TAGS = ['t'.repeat(40), '태'.repeat(30), 'x'.repeat(50), 'more'];
  const LONG_CATEGORY = '카'.repeat(40);

  /** 첫 행만 덮어쓴 목록을 연다(나중에 등록한 route 가 우선) */
  async function open(page: Page, width: number, overrides: Parameters<typeof createDataset>[0]) {
    await page.setViewportSize({ width, height: 800 });
    await setupDatasetMocks(page);
    const [first, ...rest] = createDatasets(3);
    await mockApi(page, 'GET', '/api/v1/datasets', createPageResponse([{ ...first, ...overrides }, ...rest]));
    await page.goto('/data/datasets');
  }

  for (const width of TABLE_LAYOUT_WIDTHS) {
    test(`${width}px 에서 100자 이름·긴 태그·긴 카테고리가 있어도 표가 넘치지 않고 마지막 칸까지 보인다`, async ({
      authenticatedPage: page,
    }) => {
      await open(page, width, {
        name: LONG_NAME,
        tags: LONG_TAGS,
        category: { id: 99, name: LONG_CATEGORY, description: null },
      });

      const link = page.getByRole('link', { name: LONG_NAME });
      const row = page.getByRole('row').filter({ has: link });
      await expectTableFits(row.getByRole('cell').last());
      await expectLongValueTruncated(link, LONG_NAME);
      await expectLongValueTruncated(row.getByText(LONG_CATEGORY, { exact: true }), LONG_CATEGORY);
      // 긴 태그는 배지 안에서 말줄임되고 전체 태그는 배지 title 로 남는다
      await expect(row.getByTitle(LONG_TAGS[0], { exact: true })).toBeVisible();
      // WD-658: 긴 태그가 와도 태그 칸은 상한 안이고 상한은 --k 규칙 안이다
      await expectCellWithinLongValueCap(row.getByRole('cell').filter({ has: page.getByTitle(LONG_TAGS[0], { exact: true }) }));
    });
  }

  // --k 는 rem 으로만 적어 화면 폭과 무관하다 — 폭마다 보지 않고 표당 한 번 본다(WD-650)
  test('--k 가 헤더 선언 폭 합과 맞는다 (WD-650)', async ({ authenticatedPage: page }) => {
    await open(page, 1280, { name: NAME });
    await expectKMatchesDeclaredWidths(page.getByRole('row').filter({ has: page.getByRole('link', { name: NAME }) }).getByRole('cell').last());
  });

  /**
   * 짧은 칸의 가장 넓은 값 (WD-650)
   * - 보안 등급 이름은 관리자가 정하므로 길이 제한이 없다 — 선언 폭 안에서 말줄임되고 전체 이름은 title 로 남아야 한다.
   * - 유형 칸의 가장 넓은 조합은 「테이블」+「원본」 두 배지다. 알 수 없는 유형은 원본 enum 이 그대로 와서 더 길 수 있다.
   * - 평범한 태그(「공공데이터」·finance)는 넓은 화면에서 잘리지 않아야 한다.
   */
  const LONG_LEVEL = '등'.repeat(30);
  const NORMAL_TAGS = ['공공데이터', 'finance'];
  const UNKNOWN_STORAGE = 'EXTERNAL_LAKEHOUSE_TABLE';
  // 기본 「기밀」 등급 모양에 이름만 관리자가 정한 긴 이름으로 바꾼다
  const longLevel = { ...createLevelSummary('기밀'), name: LONG_LEVEL };

  /** 긴 이름 행에 긴 보안 등급·두 유형 배지·평범한 태그를, 둘째 행에 알 수 없는 유형을 둔 목록을 연다 */
  async function openWorstShortCells(page: Page, width: number) {
    await page.setViewportSize({ width, height: 800 });
    await setupDatasetMocks(page);
    const [first, second, third] = createDatasets(3);
    await mockApi(
      page,
      'GET',
      '/api/v1/datasets',
      createPageResponse([
        {
          ...first,
          name: LONG_NAME,
          securityLevel: longLevel,
          storageType: 'TABLE',
          originType: 'SOURCE',
          tags: NORMAL_TAGS,
          category: { id: 99, name: LONG_CATEGORY, description: null },
        },
        // 서버가 새 enum 을 먼저 배포한 상황 — 타입(TABLE·DOCUMENT·FILE)에 없는 원본 값이라 타입 검사를 일부러 우회한다
        { ...second, storageType: UNKNOWN_STORAGE as DatasetResponse['storageType'] },
        third,
      ]),
    );
    await page.goto('/data/datasets');
    const link = page.getByRole('link', { name: LONG_NAME });
    return { link, row: page.getByRole('row').filter({ has: link }) };
  }

  for (const width of TABLE_LAYOUT_WIDTHS) {
    test(`${width}px 에서 긴 보안 등급·두 유형 배지·알 수 없는 유형이 있어도 표가 넘치지 않는다 (WD-650)`, async ({
      authenticatedPage: page,
    }) => {
      const { link, row } = await openWorstShortCells(page, width);
      await expectTableFits(row.getByRole('cell').last());
      await expectLongValueTruncated(link, LONG_NAME);
      // 관리자가 정한 긴 등급 이름은 배지 안에서 말줄임되고(선언 폭 안) 전체 이름은 title 로 남는다
      const levelBadge = row.getByTestId('security-level-badge');
      await expectLongValueTruncated(levelBadge.getByText(LONG_LEVEL, { exact: true }), LONG_LEVEL);
      // filter({ has }) 의 locator 는 칸 기준으로 풀리므로 page 에서 시작한다
      await expectBadgesFitDeclaredWidth(row.getByRole('cell').filter({ has: page.getByTestId('security-level-badge') }));
      // 유형 칸의 가장 넓은 조합 「테이블」+「원본」은 잘리지 않고 선언 폭 안에 들어간다
      const typeCell = row.getByRole('cell').filter({ has: page.getByText('테이블', { exact: true }) });
      await expectValueNotTruncated(typeCell.getByText('테이블', { exact: true }));
      await expectValueNotTruncated(typeCell.getByText('원본', { exact: true }));
      await expectBadgesFitDeclaredWidth(typeCell);
      // 알 수 없는 유형(원본 enum)은 칸을 넓히지 않고 배지 안에서 말줄임되며 전체 값은 title 로 남는다
      const unknownText = page.getByText(UNKNOWN_STORAGE, { exact: true });
      await expectLongValueTruncated(unknownText, UNKNOWN_STORAGE);
      await expectBadgesFitDeclaredWidth(page.getByRole('cell').filter({ has: unknownText }));
      // WD-658: 알려진 유형은 잘리지 않으므로 title 이 없다
      await expectNoTitle(typeCell);
      // 평범한 태그가 든 태그 칸도 한 줄 폭을 다 받고 상한 안이다
      await expectCellWithinLongValueCap(row.getByRole('cell').filter({ has: page.getByText(NORMAL_TAGS[1], { exact: true }) }));
    });
  }

  test('1280px 에서 평범한 태그(공공데이터·finance)는 잘리지 않는다 (WD-650)', async ({ authenticatedPage: page }) => {
    const { row } = await openWorstShortCells(page, 1280);
    await expectTableFits(row.getByRole('cell').last());
    for (const tag of NORMAL_TAGS) {
      await expectValueNotTruncated(row.getByText(tag, { exact: true }));
    }
  });

  // WD-658: 빈 등급 이름은 예전처럼 빈칸 — 「-」를 그리지 않는다
  test('보안 등급 이름이 비면 배지에 「-」를 그리지 않는다 (WD-658)', async ({ authenticatedPage: page }) => {
    await open(page, 1280, { name: NAME, securityLevel: { ...createLevelSummary('기밀'), name: '' } });
    const row = page.getByRole('row').filter({ has: page.getByRole('link', { name: NAME }) });
    const badge = row.getByTestId('security-level-badge');
    await expect(badge).toHaveText('');
    await expectNoTitle(badge);
  });

  test('1280px 에서 평범한 길이의 이름은 잘리지 않는다', async ({ authenticatedPage: page }) => {
    await open(page, 1280, { name: NAME });
    const link = page.getByRole('link', { name: NAME });
    await expectTableFits(page.getByRole('row').filter({ has: link }).getByRole('cell').last());
    await expectValueNotTruncated(link);
  });

  // WD-650·WD-658: 이름 칸은 남는 폭 전부를 쓴다 — 넘치는 이름의 링크 폭이 min(22rem, 이름 칸 안쪽 폭)과 같아야 한다(남는 폭 px 를 박지 않는다)
  test('1024px 에서 태그가 짧으면 이름이 이름 칸의 남는 폭을 다 쓴다 (WD-650)', async ({ authenticatedPage: page }) => {
    await open(page, 1024, { name: LONG_NAME, tags: NORMAL_TAGS });
    const link = page.getByRole('link', { name: LONG_NAME });
    const row = page.getByRole('row').filter({ has: link });
    await expectTableFits(row.getByRole('cell').last());
    const inner = await measureCellInnerWidth(link);
    const { linkWidth, remPx } = await link.evaluate((a) => ({
      linkWidth: a.getBoundingClientRect().width,
      remPx: parseFloat(getComputedStyle(document.documentElement).fontSize),
    }));
    expect(inner, '이름 칸 안쪽 폭(px)').toBeGreaterThan(0);
    expect(Math.abs(linkWidth - Math.min(22 * remPx, inner)), '링크 폭과 min(22rem, 안쪽 폭)의 차이(px)').toBeLessThanOrEqual(1);
    await expectLongValueTruncated(link, LONG_NAME);
    for (const tag of NORMAL_TAGS) {
      await expectValueNotTruncated(row.getByText(tag, { exact: true }));
    }
  });

  // 1024px 에서 이 이름의 잘림 여부는 남는 폭에 달려 있어 보지 않고, 표가 들어맞고 전체 이름이 title 로 남는지만 본다
  test('1024px 에서 평범한 길이의 이름이 있어도 표가 들어맞고 전체 이름은 title 로 남는다', async ({
    authenticatedPage: page,
  }) => {
    await open(page, 1024, { name: NAME });
    const link = page.getByRole('link', { name: NAME });
    await expectTableFits(page.getByRole('row').filter({ has: link }).getByRole('cell').last());
    await expect(link).toHaveAttribute('title', NAME);
  });
});
