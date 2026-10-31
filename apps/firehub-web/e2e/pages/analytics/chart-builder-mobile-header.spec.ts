/**
 * 차트 상세 머리글(모바일) E2E (WD-306)
 *
 * 결함: 휴대폰 폭(390)에서 차트 이름이 「다운로드」·「대시보드에 추가」 버튼과 한 줄을 나눠 「QA…」처럼 두세 글자만 보였고,
 * title 도 없어 전체 이름을 볼 방법이 없었다. 이제 좁은 화면에서는 이름이 뒤로 가기 버튼 옆 한 줄을 다 쓰고 버튼은 다음 줄로 내려간다.
 */

import { createChart } from '../../factories/analytics.factory';
import { setupChartBuilderMocks } from '../../fixtures/analytics.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const NAME = 'QA267 r5 박스 플롯';

test.describe('차트 상세 머리글 — 모바일 (WD-306)', () => {
  test('390 폭에서 차트 이름이 잘리지 않고 버튼은 다음 줄로 내려간다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await setupChartBuilderMocks(page, 1);
    // 나중에 등록한 라우트가 먼저 처리된다 — 이름만 이슈 재현 값으로 바꾼다
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, name: NAME }));

    await page.goto('/analytics/charts/1');
    const title = page.getByRole('heading', { level: 1, name: NAME });
    await expect(title).toBeVisible({ timeout: 10000 });

    // 말줄임 없이 전체 이름이 보인다 — 잘리면 scrollWidth 가 clientWidth 보다 크다
    const clipped = await title.evaluate((el) => el.scrollWidth > el.clientWidth);
    expect(clipped).toBe(false);
    // 그래도 잘리는 긴 이름을 위해 전체 이름 툴팁
    await expect(title).toHaveAttribute('title', NAME);

    // 「다운로드」 버튼은 제목 아래 줄에 있다
    const titleBox = await title.boundingBox();
    const downloadBox = await page.getByRole('button', { name: '차트 다운로드' }).boundingBox();
    expect(titleBox && downloadBox).toBeTruthy();
    expect(downloadBox!.y).toBeGreaterThan(titleBox!.y + titleBox!.height - 1);
  });

  test('넓은 화면에서는 이름과 버튼이 지금처럼 한 줄에 놓인다', async ({ authenticatedPage: page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await setupChartBuilderMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, name: NAME }));

    await page.goto('/analytics/charts/1');
    const title = page.getByRole('heading', { level: 1, name: NAME });
    await expect(title).toBeVisible({ timeout: 10000 });

    const titleBox = await title.boundingBox();
    const downloadBox = await page.getByRole('button', { name: '차트 다운로드' }).boundingBox();
    // 같은 줄 — 버튼 세로 범위가 제목 세로 범위와 겹친다
    expect(downloadBox!.y).toBeLessThan(titleBox!.y + titleBox!.height);
  });
});
