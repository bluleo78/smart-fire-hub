import type { Page } from '@playwright/test';

import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 대시보드 위젯 높이 채우기 (WD-845)
 *
 * 폭포·트리맵·박스플롯·레이더·히스토그램·퍼널·캔들·히트맵 8개 뷰가 `height = 300` 기본값을 가져
 * 위젯 높이와 상관없이 그림이 300px 로 고정되던 문제를 검증한다. 위젯 행 수를 작게/크게 두 번 그려
 * 그림 높이가 (a) 300 이 아니고 (b) 큰 위젯이 더 크며 (c) 위젯 본문을 넘지 않는지 본다.
 */

type Case = {
  type: string;
  columns: string[];
  rows: Record<string, unknown>[];
  config: Record<string, unknown>;
  /** 그림 영역 선택자(드로잉 영역 안) */
  target: string;
};

const CATS = ['A', 'B', 'C', 'D'];
const cat = (fn: (i: number) => Record<string, unknown>) => CATS.map((c, i) => ({ name: c, ...fn(i) }));

const CASES: Case[] = [
  { type: 'WATERFALL', columns: ['name', 'v'], rows: cat((i) => ({ v: (i + 1) * 10 - 15 })), config: { xAxis: 'name', yAxis: ['v'] }, target: '.recharts-wrapper' },
  { type: 'TREEMAP', columns: ['name', 'v'], rows: cat((i) => ({ v: (i + 1) * 10 })), config: { xAxis: 'name', yAxis: ['v'] }, target: '.recharts-wrapper' },
  {
    type: 'BOXPLOT',
    columns: ['name', 'min', 'q1', 'median', 'q3', 'max'],
    rows: cat((i) => ({ min: 1, q1: 2 + i, median: 4 + i, q3: 6 + i, max: 10 + i })),
    config: { xAxis: 'name', yAxis: ['median'] },
    target: 'svg',
  },
  { type: 'RADAR', columns: ['name', 'v'], rows: cat((i) => ({ v: (i + 1) * 10 })), config: { xAxis: 'name', yAxis: ['v'] }, target: '.recharts-wrapper' },
  { type: 'HISTOGRAM', columns: ['v'], rows: Array.from({ length: 30 }, (_, i) => ({ v: i * 3 })), config: { xAxis: 'v', yAxis: ['v'], bins: 5 }, target: '.recharts-wrapper' },
  { type: 'FUNNEL', columns: ['name', 'v'], rows: cat((i) => ({ v: 100 - i * 20 })), config: { xAxis: 'name', yAxis: ['v'] }, target: '.recharts-wrapper' },
  {
    type: 'CANDLESTICK',
    columns: ['name', 'o', 'h', 'l', 'c'],
    rows: cat((i) => ({ o: 10 + i, h: 15 + i, l: 8 + i, c: 12 + i })),
    config: { xAxis: 'name', yAxis: ['c'], open: 'o', high: 'h', low: 'l', close: 'c' },
    target: 'svg',
  },
  {
    type: 'HEATMAP',
    columns: ['r', 'col', 'val'],
    rows: ['a', 'b', 'c'].flatMap((r) => ['x', 'y', 'z'].map((c, i) => ({ r, col: c, val: i + 1 }))),
    config: { xAxis: 'r', yAxis: ['col'], valueColumn: 'val' },
    target: 'svg',
  },
];

async function setup(page: Page, c: Case, widgetHeight: number, rows = c.rows, columns = c.columns) {
  const widget = createWidget({ id: 1, chartId: 1, chartName: `위젯-${c.type}`, chartType: c.type as never, width: 6, height: widgetHeight });
  const chart = createChart({ id: 1, name: `위젯-${c.type}`, chartType: c.type as never, config: c.config as never });
  await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, widgets: [widget] }));
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', {
    chart,
    queryResult: createQueryResult({ columns, rows: rows as never, totalRows: rows.length }),
  });
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chart);
  await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([], { size: 20 }));
}

/** 위젯 본문(차트 영역) testid */
const BODY = 'dashboard-widget-chart-area';

/** 측정 대상의 높이와, 그것을 담는 위젯 본문 높이. 레이아웃이 안정(두 번 연속 같은 값)될 때까지 poll 한다. */
async function measure(page: Page, selector: string) {
  const el = page.locator(`[data-chart-drawing] ${selector}`).first();
  await expect(el).toBeVisible();
  const body = page.getByTestId(BODY);
  const read = async () => ({
    h: (await el.boundingBox())?.height ?? 0,
    body: (await body.boundingBox())?.height ?? 0,
  });
  let last = { h: -1, body: -1 };
  await expect
    .poll(async () => {
      const cur = await read();
      const stable = cur.h === last.h && cur.body === last.body && cur.h > 0;
      last = cur;
      return stable;
    })
    .toBe(true);
  return last;
}

test.describe('대시보드 위젯 높이 채우기 (WD-845)', () => {
  for (const c of CASES) {
    test(`${c.type} 그림이 위젯 높이를 채운다(300px 고정 아님)`, async ({ authenticatedPage: page }) => {
      await setup(page, c, 3);
      await page.goto('/analytics/dashboards/1');
      const small = await measure(page, c.target);

      await setup(page, c, 7);
      await page.goto('/analytics/dashboards/1');
      const large = await measure(page, c.target);

      // 그림 높이가 위젯 본문 높이와 거의 같고(300 고정이 아님), 큰 위젯이 더 크다
      expect(Math.abs(small.h - small.body)).toBeLessThanOrEqual(8);
      expect(Math.abs(large.h - large.body)).toBeLessThanOrEqual(8);
      expect(large.h).toBeGreaterThan(small.h);
    });
  }

  // 박스플롯·캔들·히트맵: 본문이 위·아래 여백 합(60px) 이하인 아주 낮은 위젯(1행)에서는 뒤집혀 그리지 않는다.
  // 컴포넌트 단위 테스트 환경이 없어 E2E 로 검증한다 — 측정이 끝난 뒤에도 svg 를 하나도 그리면 안 된다.
  // 그릴 높이가 여백 합 이하면 그리지 않는 가드가 있는 차트들(WD-845)
  const LOW_GUARD_TYPES = ['BOXPLOT', 'CANDLESTICK', 'HEATMAP'];
  for (const c of CASES.filter((x) => LOW_GUARD_TYPES.includes(x.type))) {
    test(`${c.type} 아주 낮은 위젯에서는 음수 높이로 그리지 않는다`, async ({ authenticatedPage: page }) => {
      await setup(page, c, 1);
      await page.goto('/analytics/dashboards/1');
      const body = page.getByTestId(BODY);
      await expect(body).toBeVisible();
      await expect.poll(async () => (await body.boundingBox())?.height ?? 0).toBeGreaterThan(0);
      const bodyH = (await body.boundingBox())?.height ?? 0;
      expect(bodyH).toBeLessThanOrEqual(60);
      // 측정이 끝난 뒤에 검사한다 — 측정 전의 빈 상태로 통과하지 않게(WD-845). 빈 div 라 visible 이 아닐 수 있어 개수로 본다
      await expect(body.locator('[data-chart-measured="true"]')).toHaveCount(1);
      await expect(body.locator('[data-chart-drawing] svg')).toHaveCount(0);
    });
  }

  test('폭포 넘침 안내가 위젯 높이를 채운다', async ({ authenticatedPage: page }) => {
    const c = CASES.find((x) => x.type === 'WATERFALL')!;
    const rows = [{ name: 'A', v: 1e308 }, { name: 'B', v: 1e308 }, { name: 'C', v: -5 }];
    const get = async (h: number) => {
      await setup(page, c, h, rows);
      await page.goto('/analytics/dashboards/1');
      const el = page.getByTestId('waterfall-overflow-notice');
      await expect(el).toBeVisible();
      const read = async () => ({
        h: (await el.boundingBox())?.height ?? 0,
        body: (await page.getByTestId(BODY).boundingBox())?.height ?? 0,
      });
      // 폴링 한 번에 read() 를 한 번만 부르고, 마지막으로 읽은 값을 그대로 돌려준다
      let last = await read();
      await expect.poll(async () => {
        last = await read();
        return Math.abs(last.h - last.body);
      }).toBeLessThanOrEqual(8);
      return last;
    };
    const small = await get(3);
    const large = await get(7);
    expect(Math.abs(small.h - small.body)).toBeLessThanOrEqual(8);
    expect(Math.abs(large.h - large.body)).toBeLessThanOrEqual(8);
    expect(large.h).toBeGreaterThan(small.h);
  });
});
