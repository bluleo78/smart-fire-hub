import type { Page } from '@playwright/test';

import { createDataset, createDatasets } from '../../factories/dataset.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';
import {
  expectLongValueTruncated,
  expectTableFits,
  expectValueNotTruncated,
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
    });
  }

  test('1280px 에서 평범한 길이의 이름은 잘리지 않는다', async ({ authenticatedPage: page }) => {
    await open(page, 1280, { name: NAME });
    const link = page.getByRole('link', { name: NAME });
    await expectTableFits(page.getByRole('row').filter({ has: link }).getByRole('cell').last());
    await expectValueNotTruncated(link);
  });

  // 1024px 은 짧은 칸(즐겨찾기·보안 등급·태그·유형·카테고리·생성일·작업)의 선언 폭을 빼고 남는 폭이 평범한 이름보다 좁다 —
  // 표가 들어맞고 이름 전체가 title 로 남는지만 본다.
  test('1024px 에서 평범한 길이의 이름이 있어도 표가 들어맞고 전체 이름은 title 로 남는다', async ({
    authenticatedPage: page,
  }) => {
    await open(page, 1024, { name: NAME });
    const link = page.getByRole('link', { name: NAME });
    await expectTableFits(page.getByRole('row').filter({ has: link }).getByRole('cell').last());
    await expect(link).toHaveAttribute('title', NAME);
  });
});
