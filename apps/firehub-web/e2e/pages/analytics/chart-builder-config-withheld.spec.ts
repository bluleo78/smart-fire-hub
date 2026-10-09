/**
 * 차트 빌더 — 설정이 가려진 차트(configWithheld)의 편집 잠금 E2E (WD-31②)
 *
 * 조회자가 저장 쿼리의 데이터를 볼 수 없으면 서버가 config 를 null 로 준다. 빌더는 설정 패널을 잠그고 안내를 보이며,
 * 저장할 때 config·chartType 을 보내지 않아 기존 설정을 덮어쓰지 않아야 한다.
 */
import type { Page } from '@playwright/test';

import { createChart } from '../../factories/analytics.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const NOTICE = '이 차트의 설정은 관련 데이터를 볼 수 있는 사용자에게만 표시됩니다';

/** 설정이 가려진 차트 5번과 빈 저장 쿼리 목록을 모킹한다 */
async function mockWithheldChart(page: Page) {
  await mockApi(
    page,
    'GET',
    '/api/v1/analytics/charts/5',
    createChart({ id: 5, name: '가려진 차트', config: null, configWithheld: true, savedQueryId: 9 }),
  );
  await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse([]));
}

test.describe('차트 빌더 — 설정 가림 잠금 (WD-31②)', () => {
  test('잠금 안내가 보이고 차트 타입·축 설정·쿼리 실행·다운로드를 쓸 수 없다', async ({
    authenticatedPage: page,
  }) => {
    await mockWithheldChart(page);

    await page.goto('/analytics/charts/5');

    // 좌측 패널과 미리보기 두 곳에 같은 안내가 보인다
    await expect(page.getByTestId('chart-config-withheld')).toHaveCount(2);
    await expect(page.getByText(NOTICE).first()).toBeVisible();
    // 차트 타입·축 설정 카드는 렌더링하지 않는다
    await expect(page.getByText('차트 타입', { exact: true })).toHaveCount(0);
    await expect(page.getByText('축 설정', { exact: true })).toHaveCount(0);
    // 쿼리를 바꾸거나 실행해 보이지 않는 설정과 어긋나게 만들 수 없다
    await expect(page.getByRole('button', { name: '쿼리 실행' })).toBeDisabled();
    await expect(page.getByRole('combobox', { name: '저장된 쿼리' })).toBeDisabled();
    // 그릴 미리보기가 없으니 다운로드 버튼도 두지 않는다
    await expect(page.getByRole('button', { name: '차트 다운로드' })).toHaveCount(0);
    await page.screenshot({ path: 'test-results/tc/chart-config-withheld/locked.png', fullPage: true });
  });

  test('저장하면 config·chartType 없이 이름·설명·공유만 보낸다', async ({ authenticatedPage: page }) => {
    await mockWithheldChart(page);
    const put = await mockApi(
      page,
      'PUT',
      '/api/v1/analytics/charts/5',
      createChart({ id: 5, name: '바뀐 이름', config: null, configWithheld: true }),
      { capture: true },
    );

    await page.goto('/analytics/charts/5');
    await expect(page.getByText(NOTICE).first()).toBeVisible();
    await page.getByRole('button', { name: '저장' }).click();
    const dialog = page.getByRole('dialog');
    // 잠금 상태에서는 설정이 저장되지 않는다는 설명을 보인다
    await expect(dialog.getByText('이름·설명·공유만 바꿀 수 있습니다.')).toBeVisible();
    await dialog.getByLabel('이름').fill('바뀐 이름');
    await dialog.getByRole('button', { name: '수정' }).click();

    const req = await put.waitForRequest();
    expect(req.payload).toMatchObject({ name: '바뀐 이름', isShared: false });
    expect(req.payload).not.toHaveProperty('config');
    expect(req.payload).not.toHaveProperty('chartType');
    await expect(page.getByText('차트가 수정되었습니다.')).toBeVisible();
  });

  test('설정이 보이는 차트는 기존처럼 config·chartType 을 함께 보낸다 (대조군)', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts/6',
      createChart({ id: 6, config: { xAxis: 'name', yAxis: ['value'] } }),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse([]));
    const put = await mockApi(page, 'PUT', '/api/v1/analytics/charts/6', createChart({ id: 6 }), { capture: true });

    await page.goto('/analytics/charts/6');
    await expect(page.getByTestId('chart-config-withheld')).toHaveCount(0);
    await page.getByRole('button', { name: '저장' }).click();
    await page.getByRole('dialog').getByRole('button', { name: '수정' }).click();

    const req = await put.waitForRequest();
    expect(req.payload).toMatchObject({ chartType: 'BAR', config: { xAxis: 'name', yAxis: ['value'] } });
  });
});
