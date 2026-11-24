import type { Page, Route } from '@playwright/test';

import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 위젯 그리기 차례 큐 회귀 테스트 (WD-1072)
 *
 * 위젯 여러 개가 한 커밋에서 recharts 를 모두 그리면 메인 스레드가 길게 막힌다.
 * 위젯은 차례를 받아 하나씩 그리되, (1) 모든 위젯이 결국 그려지고 (2) 한 번 그린 위젯은
 * 새로고침 중에도 스피너로 돌아가지 않아야 한다.
 */

// 위젯 6개(3행 x 4칸 = 약 1500px)가 스크롤 없이 모두 보이게 연다 — 화면 밖 위젯은 조회를 미루고, 부하 아래 scrollIntoView 가 흔들렸다
test.use({ viewport: { width: 1280, height: 1800 } });

const TYPES = ['BAR', 'LINE', 'AREA', 'SCATTER', 'PIE', 'BAR'] as const;

/** 120행 데이터(애니메이션 끄는 기준 100행 초과) — cat(문자)은 막대·선·영역·파이, n(숫자)은 산점도 X 축이다 */
const rows = Array.from({ length: 120 }, (_, i) => ({ cat: `c${i}`, n: i, v: (i * 7) % 100 }));

const charts = TYPES.map((chartType, i) =>
  createChart({ id: i + 1, name: `위젯 ${i + 1}`, chartType, config: { xAxis: chartType === 'SCATTER' ? 'n' : 'cat', yAxis: ['v'] } }),
);

const dataBody = (chart: (typeof charts)[number]) => ({
  chart,
  queryResult: createQueryResult({ columns: ['cat', 'n', 'v'], rows, totalRows: rows.length }),
});

/** 위젯 6개 대시보드 모킹(viewport 를 키워 모두 화면 안에 둔다). 차트별 단건 데이터 라우트는 onData 로 가로챌 수 있다. */
async function setup(page: Page, onData?: (chartId: number, route: Route) => Promise<void> | void) {
  const dashboard = createDashboard({
    id: 1,
    widgets: charts.map((c, i) =>
      createWidget({
        id: 100 + i,
        chartId: c.id,
        chartName: c.name,
        chartType: c.chartType,
        positionX: (i % 2) * 6,
        positionY: Math.floor(i / 2) * 4,
        width: 6,
        height: 4,
      }),
    ),
  });
  await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', dashboard);
  await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));
  for (const c of charts) {
    await mockApi(page, 'GET', `/api/v1/analytics/charts/${c.id}`, c);
    await page.route(
      (url) => url.pathname === `/api/v1/analytics/charts/${c.id}/data`,
      async (route) => {
        if (onData) await onData(c.id, route);
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(dataBody(c)) });
      },
    );
  }
}

const card = (page: Page, name: string) =>
  page.locator('[data-slot="card"]').filter({ has: page.getByText(name, { exact: true }) });

test.describe('대시보드 위젯 — 그리기 차례 큐 (WD-1072)', () => {
  test('위젯 6개(각 120행)가 모두 차례대로 그려진다', async ({ authenticatedPage: page }) => {
    test.setTimeout(60_000);
    await setup(page);
    await page.goto('/analytics/dashboards/1');

    await expect(page.locator('[data-testid="chart-figure"][data-has-drawing="true"]')).toHaveCount(6, { timeout: 30_000 });
  });

  test('새로고침 응답이 늦어도 이미 그린 위젯의 차트가 스피너로 돌아가지 않는다', async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(60_000);
    let hold = false;
    let release: () => void = () => {};
    const released = new Promise<void>((resolve) => {
      release = resolve;
    });
    await setup(page, async (chartId) => {
      if (hold && chartId === 1) await released;
    });
    await page.goto('/analytics/dashboards/1');
    await expect(page.locator('[data-testid="chart-figure"][data-has-drawing="true"]')).toHaveCount(6, { timeout: 30_000 });

    const target = card(page, '위젯 1');
    hold = true;
    // 새로고침이 보낸 위젯 1 의 데이터 응답 도착을 직접 기다린다(문구 '방금' 에 기대지 않음)
    const requested = page.waitForRequest((r) => new URL(r.url()).pathname === '/api/v1/analytics/charts/1/data');
    const refreshed = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/analytics/charts/1/data');
    await target.locator('button[title="새로고침"]').click();

    // 응답을 붙잡은 동안에도 그 위젯의 figure 는 그대로 그려져 있다
    // 새로고침 요청이 실제로 나간 뒤(응답은 붙잡힌 상태)에 확인한다 — 고정 대기 대신 관찰 가능한 조건
    await requested;
    await expect(target.locator('[data-testid="chart-figure"][data-has-drawing="true"]')).toHaveCount(1);
    release();
    await refreshed;
    await expect(target.locator('[data-testid="chart-figure"][data-has-drawing="true"]')).toHaveCount(1);
  });

  test('행 100개 넘는 막대 차트는 애니메이션 없이 바로 그려진다 (WD-1072)', async ({ authenticatedPage: page }) => {
    await setup(page);
    await page.goto('/analytics/dashboards/1');

    const bar = card(page, '위젯 1').locator('.recharts-bar-rectangle path').first();
    await bar.waitFor({ state: 'attached', timeout: 30_000 });
    // 막대가 생긴 직후의 d 를 읽는다 — 가시성 대기 동안 애니메이션이 끝나 거짓 통과하지 않게.
    // 애니메이션이 켜져 있으면 막대 높이가 프레임마다 변해 d 가 달라진다
    const before = await bar.getAttribute('d');
    await page.waitForTimeout(600);
    expect(await bar.getAttribute('d')).toBe(before);
  });
});
