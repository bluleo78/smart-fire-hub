import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * WD-195 — 볼 수 없는 차트의 위젯.
 * 서버는 조회자가 볼 수 없는(비공개 남의) 차트의 위젯을 이름·종류 없이 accessible=false 로 주고 자리(위치·크기)만 남긴다.
 * 웹은 그 위젯을 「새로고침 실패」 오류가 아니라 안내 상태로 그리고, 차트 메타·데이터를 조회하지 않는다(조회하면 404).
 */
test.describe('대시보드 위젯 — 볼 수 없는 차트 (WD-195)', () => {
  const HIDDEN_CHART_ID = 7;

  /** 볼 수 없는 위젯(차트 7) + 정상 위젯(차트 2)이 나란히 있는 공유 대시보드를 모킹한다 */
  async function setup(page: import('@playwright/test').Page) {
    const hidden = createWidget({
      id: 3,
      chartId: HIDDEN_CHART_ID,
      chartName: null,
      chartType: null,
      accessible: false,
      positionX: 6,
      width: 6,
      height: 4,
    });
    const ok = createWidget({ id: 2, chartId: 2, chartName: '출동 건수', positionX: 0, width: 6, height: 4 });
    const okChart = createChart({ id: 2, name: '출동 건수', config: { xAxis: 'm', yAxis: ['v'] } });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, isShared: true, widgets: [ok, hidden] }));
    await mockApi(page, 'GET', '/api/v1/analytics/charts/2', okChart);
    await mockApi(page, 'GET', '/api/v1/analytics/charts/2/data', {
      chart: okChart,
      queryResult: createQueryResult({ columns: ['m', 'v'], rows: [{ m: '1월', v: 3 }], totalRows: 1 }),
      exportAllowed: true,
    });
    await mockApi(page, 'GET', '/api/v1/analytics/charts', { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });

    // 볼 수 없는 차트의 메타·데이터 요청이 나가는지 센다 — 나가면 404 로 「새로고침 실패」가 붙던 원인
    const hiddenRequests: string[] = [];
    page.on('request', (req) => {
      if (new RegExp(`/api/v1/analytics/charts/${HIDDEN_CHART_ID}(/data)?(\\?|$)`).test(req.url())) {
        hiddenRequests.push(req.url());
      }
    });
    return { hiddenRequests };
  }

  test('조회자 화면 — 안내 상태로 보이고 차트 조회·새로고침 실패가 없다', async ({ authenticatedPage: page }) => {
    const { hiddenRequests } = await setup(page);

    await page.goto('/analytics/dashboards/1');
    const hiddenCard = page.locator('[data-slot="card"]').filter({ has: page.getByTestId('widget-inaccessible') });
    await expect(hiddenCard).toBeVisible();
    // 제목은 이름 대신 「볼 수 없는 차트」, 본문은 원인 안내
    await expect(hiddenCard.locator('[data-slot="card-title"]')).toHaveText('볼 수 없는 차트');
    await expect(hiddenCard.getByTestId('widget-inaccessible')).toContainText('볼 수 없는 차트입니다');
    await expect(hiddenCard.getByTestId('widget-inaccessible')).toContainText('차트 주인이 공유하지 않았습니다.');
    // 오류·재시도·신선도 바 없음
    await expect(hiddenCard.getByText('데이터를 불러올 수 없습니다.')).toHaveCount(0);
    await expect(hiddenCard.getByText('새로고침 실패')).toHaveCount(0);
    await expect(hiddenCard.getByRole('button', { name: /새로고침|다시 시도/ })).toHaveCount(0);

    // 이웃 위젯은 정상 — 위 '새로고침 버튼 없음' 단언이 공허하지 않게 정상 위젯에는 버튼이 있음을 함께 본다
    const okCard = page.locator('[data-slot="card"]').filter({ hasText: '출동 건수' });
    await expect(okCard.getByRole('button', { name: '새로고침' })).toHaveCount(1);

    // 자리 유지 — 볼 수 없는 위젯(positionX=6)이 정상 위젯(positionX=0) 오른쪽 같은 줄에 있다
    const okBox = await okCard.boundingBox();
    const hiddenBox = await hiddenCard.boundingBox();
    expect(okBox && hiddenBox).toBeTruthy();
    expect(hiddenBox!.x).toBeGreaterThan(okBox!.x + okBox!.width / 2);
    expect(Math.abs(hiddenBox!.y - okBox!.y)).toBeLessThan(5);

    // 헤더 새로고침도 볼 수 없는 차트를 다시 조회하지 않고, 실패 토스트를 띄우지 않는다
    // (헤더 버튼이 DOM 상 첫 「새로고침」이다 — 정상 위젯 데이터를 실제로 다시 조회하는지 함께 봐서 클릭이 공허하지 않게 한다)
    const okRefetch = page.waitForRequest((req) => /\/api\/v1\/analytics\/charts\/2\/data(\?|$)/.test(req.url()));
    await page.locator('button[title="새로고침"]').first().click();
    await okRefetch;
    await expect(page.getByText('위젯 데이터를 불러오지 못했습니다.')).toHaveCount(0);

    // 볼 수 있는 위젯만 있으면 PDF 내보내기는 그대로 노출 — 볼 수 없는 위젯은 내보낼 데이터가 없다
    await expect(page.getByRole('button', { name: 'PDF로 내보내기' })).toBeVisible();

    expect(hiddenRequests).toEqual([]);
  });

  test('대시보드 주인 — 편집 모드에서 볼 수 없게 된 위젯을 삭제할 수 있다', async ({ authenticatedPage: page }) => {
    const { hiddenRequests } = await setup(page);
    const deleteCapture = await mockApi(page, 'DELETE', '/api/v1/analytics/dashboards/1/widgets/3', {}, { capture: true });

    await page.goto('/analytics/dashboards/1');
    await page.getByRole('button', { name: '편집' }).click();

    const hiddenCard = page.locator('[data-slot="card"]').filter({ has: page.getByTestId('widget-inaccessible') });
    await hiddenCard.locator('button[title="위젯 제거"]').click();
    await expect(page.getByRole('alertdialog')).toBeVisible();
    await page.getByRole('alertdialog').getByRole('button', { name: '삭제' }).click();

    const captured = await deleteCapture.waitForRequest();
    expect(captured.url.href).toContain('/api/v1/analytics/dashboards/1/widgets/3');
    await expect(page.getByText('위젯이 제거되었습니다.')).toBeVisible({ timeout: 5000 });
    expect(hiddenRequests).toEqual([]);
  });
});
