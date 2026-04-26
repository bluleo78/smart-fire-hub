/**
 * 파이프라인 목록 — 모바일 가로 스크롤 인디케이터 회귀 테스트 (refs #61)
 * - 375px 모바일 뷰포트에서 테이블이 viewport 너비를 초과할 때
 *   우측 페이드 그라데이션이 노출되어 잘린 컬럼 존재를 사용자에게 알려야 한다.
 */
import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineMocks } from '../../fixtures/pipeline.fixture';

test.describe('파이프라인 목록 — 모바일 가로 스크롤 인디케이터 (refs #61)', () => {
  test('모바일(375px)에서 우측 페이드 인디케이터가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await setupPipelineMocks(page, 5);
    await page.goto('/pipelines');

    // 데이터 행 렌더링 대기
    await expect(
      page.getByRole('cell', { name: '파이프라인 1', exact: true }),
    ).toBeVisible();

    // 테이블이 viewport보다 넓은지 확인 (재현 조건)
    const dims = await page
      .locator('[data-slot="table-container"]')
      .first()
      .evaluate((el) => ({
        scrollWidth: el.scrollWidth,
        clientWidth: el.clientWidth,
      }));
    expect(dims.scrollWidth).toBeGreaterThan(dims.clientWidth);

    // 우측 페이드 표시 확인
    await expect(page.locator('[data-slot="table-fade-right"]').first()).toBeVisible();
  });

  test('데스크톱에서는 페이드 인디케이터가 표시되지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await setupPipelineMocks(page, 5);
    await page.goto('/pipelines');

    await expect(
      page.getByRole('cell', { name: '파이프라인 1', exact: true }),
    ).toBeVisible();

    await expect(page.locator('[data-slot="table-fade-right"]')).toHaveCount(0);
    await expect(page.locator('[data-slot="table-fade-left"]')).toHaveCount(0);
  });
});
