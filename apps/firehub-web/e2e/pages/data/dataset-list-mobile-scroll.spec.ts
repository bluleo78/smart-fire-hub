/**
 * 데이터셋 목록 — 모바일 가로 스크롤 인디케이터 회귀 테스트 (refs #61)
 * - 375px 모바일 뷰포트에서 테이블이 viewport 너비를 초과할 때
 *   우측 페이드 그라데이션이 노출되어 사용자에게 잘린 컬럼 존재를 알려야 한다.
 * - 스크롤 후에는 좌측 페이드도 함께 표시되어 좌우 양방향 스크롤 가능을 시각화한다.
 */
import { expect, test } from '../../fixtures/auth.fixture';
import { setupDatasetMocks } from '../../fixtures/dataset.fixture';

test.describe('데이터셋 목록 — 모바일 가로 스크롤 인디케이터 (refs #61)', () => {
  test('모바일(375px)에서 테이블이 viewport보다 넓을 때 우측 페이드 인디케이터가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    // 데이터 행이 렌더링될 때까지 대기
    await expect(page.getByRole('row', { name: /데이터셋 1/ })).toBeVisible();

    // 테이블 컨테이너가 실제로 가로 스크롤이 필요한 상태인지 확인 (재현 조건)
    const dims = await page
      .locator('[data-slot="table-container"]')
      .first()
      .evaluate((el) => ({
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
      }));
    expect(dims.scrollWidth).toBeGreaterThan(dims.clientWidth);

    // 우측 페이드 그라데이션이 DOM에 존재해야 한다 (인디케이터)
    await expect(page.locator('[data-slot="table-fade-right"]').first()).toBeVisible();
    // 초기에는 좌측 페이드는 없어야 한다 (scrollLeft = 0)
    await expect(page.locator('[data-slot="table-fade-left"]')).toHaveCount(0);
  });

  test('가로 스크롤 후 좌측 페이드가 추가로 노출된다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    await expect(page.getByRole('row', { name: /데이터셋 1/ })).toBeVisible();

    // 컨테이너를 우측으로 스크롤
    await page.locator('[data-slot="table-container"]').first().evaluate((el) => {
      el.scrollLeft = 100;
    });

    // 좌측 페이드 표시 확인 (양방향 스크롤 가능 시각화)
    await expect(page.locator('[data-slot="table-fade-left"]').first()).toBeVisible();
  });

  test('데스크톱 뷰포트에서는 테이블이 viewport 안에 들어가 페이드 인디케이터가 표시되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await setupDatasetMocks(page);
    await page.goto('/data/datasets');

    await expect(page.getByRole('row', { name: /데이터셋 1/ })).toBeVisible();

    const dims = await page
      .locator('[data-slot="table-container"]')
      .first()
      .evaluate((el) => ({
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
      }));
    // 데스크톱에서는 테이블이 컨테이너 안에 들어와야 한다 (스크롤 불필요)
    expect(dims.scrollWidth).toBeLessThanOrEqual(dims.clientWidth);

    // 페이드 인디케이터가 표시되지 않아야 한다
    await expect(page.locator('[data-slot="table-fade-right"]')).toHaveCount(0);
    await expect(page.locator('[data-slot="table-fade-left"]')).toHaveCount(0);
  });
});
