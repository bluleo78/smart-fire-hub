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
 *
 * E2E 는 dev StrictMode 라 차례 순서를 보지 못한다 — 순서는 단위 시험(widget-render-queue.test.ts)이 본다(WD-1113).
 *
 * 「표 보기·0행」 시험은 차례를 이미 받은 뒤의 동작 — 스피너 없이 다시 그리거나 빈 안내를 보이는지 — 만 기록한다.
 * 한 번 받은 차례는 계속 true 라 ready 가 바뀌어도 E2E 결과는 같다. 그래서 「그릴 차트가 있을 때만 차례를 기다린다」는
 * ready 규칙(chartNeedsRenderTurn)은 E2E 가 아니라 단위 시험(chart-render-turn.test.ts, DashboardWidgetCard.test.tsx)만 지킨다.
 */

// 위젯 6개(3행 x 4칸 = 약 1500px)가 스크롤 없이 모두 보이게 연다 — 화면 밖 위젯은 조회를 미루고, 부하 아래 scrollIntoView 가 흔들렸다
test.use({ viewport: { width: 1280, height: 1800 } });

type ChartType = 'BAR' | 'LINE' | 'AREA' | 'SCATTER' | 'PIE' | 'TABLE';
const TYPES: ChartType[] = ['BAR', 'LINE', 'AREA', 'SCATTER', 'PIE', 'BAR'];
/** 종류마다 위젯 하나씩 — 6번째가 표(TABLE) 위젯이다 */
const ALL_TYPES: ChartType[] = ['BAR', 'LINE', 'AREA', 'SCATTER', 'PIE', 'TABLE'];

/** 120행 데이터(애니메이션 끄는 기준 100행 초과) — cat(문자)은 막대·선·영역·파이, n(숫자)은 산점도 X 축이다 */
const rows = Array.from({ length: 120 }, (_, i) => ({ cat: `c${i}`, n: i, v: (i * 7) % 100 }));

/** 위젯 종류별 차트 정의 — 6번째를 바꿔 표(TABLE) 위젯 변형을 만든다 */
const makeCharts = (types: ChartType[]) =>
  types.map((chartType, i) =>
    createChart({ id: i + 1, name: `위젯 ${i + 1}`, chartType, config: { xAxis: chartType === 'SCATTER' ? 'n' : 'cat', yAxis: ['v'] } }),
  );

interface SetupOptions {
  /** 차트별 단건 데이터 라우트 가로채기(응답을 붙잡을 때 쓴다) */
  onData?: (chartId: number, route: Route) => Promise<void> | void;
  /** 위젯 종류 덮어쓰기 */
  types?: ChartType[];
  /** 차트별 응답 행 덮어쓰기 — undefined 를 돌려주면 기본 120행 */
  rowsOf?: (chartId: number) => typeof rows | undefined;
}

/** 차트 단건 데이터 응답 본문 */
const dataBody = (chart: ReturnType<typeof createChart>, chartRows: typeof rows) =>
  JSON.stringify({
    chart,
    queryResult: createQueryResult({ columns: ['cat', 'n', 'v'], rows: chartRows, totalRows: chartRows.length }),
  });

/** 위젯 6개 대시보드 모킹(viewport 를 키워 모두 화면 안에 둔다). 차트별 단건 데이터 라우트는 onData 로 가로챌 수 있다. */
async function setup(page: Page, { onData, types = TYPES, rowsOf }: SetupOptions = {}) {
  const charts = makeCharts(types);
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
        const chartRows = rowsOf?.(c.id) ?? rows;
        return route.fulfill({ status: 200, contentType: 'application/json', body: dataBody(c, chartRows) });
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
    await setup(page, {
      onData: async (chartId) => {
        if (hold && chartId === 1) await released;
      },
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

  // 차례를 받은 뒤의 동작만 기록한다 — 차례는 이미 받아 true 로 남으므로 ready 규칙(chartNeedsRenderTurn)은 여기서 검증되지 않는다(단위 시험 담당).
  test('표로 보기를 켰다 끄면 이미 그린 차트가 스피너 없이 바로 다시 그려진다 (WD-1113)', async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(60_000);
    await setup(page);
    await page.goto('/analytics/dashboards/1');
    await expect(page.locator('[data-testid="chart-figure"][data-has-drawing="true"]')).toHaveCount(6, { timeout: 30_000 });

    const target = card(page, '위젯 1');
    const toggle = target.getByRole('button', { name: '표로 보기' });
    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-pressed', 'true');
    await expect(target.getByRole('table')).toBeVisible();

    await toggle.click();
    await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    await expect(target.locator('[data-testid="chart-figure"][data-has-drawing="true"]')).toHaveCount(1);
    await expect(target.locator('[data-testid="dashboard-widget-chart-area"] .animate-spin')).toHaveCount(0);
  });

  // 차례를 받은 뒤의 동작만 기록한다 — 0행 동안 ready 가 false 가 돼도 받은 차례는 남으므로 ready 규칙은 단위 시험이 지킨다.
  test('0행으로 새로고침됐다가 다시 행을 받으면 스피너 없이 그린다 (WD-1113)', async ({ authenticatedPage: page }) => {
    test.setTimeout(60_000);
    let empty = false;
    await setup(page, { rowsOf: (chartId) => (empty && chartId === 1 ? [] : undefined) });
    await page.goto('/analytics/dashboards/1');
    await expect(page.locator('[data-testid="chart-figure"][data-has-drawing="true"]')).toHaveCount(6, { timeout: 30_000 });

    const target = card(page, '위젯 1');
    const area = target.locator('[data-testid="dashboard-widget-chart-area"]');

    // 다음 응답을 0행으로 — 새로고침하면 빈 안내가 보이고 스피너에 갇히지 않는다
    empty = true;
    await target.locator('button[title="새로고침"]').click();
    await expect(target.getByText('데이터가 없습니다.')).toBeVisible();
    await expect(area.locator('.animate-spin')).toHaveCount(0);

    // 다시 행을 받으면 차트가 그려진다
    empty = false;
    await target.locator('button[title="새로고침"]').click();
    await expect(target.locator('[data-testid="chart-figure"][data-has-drawing="true"]')).toHaveCount(1);
    await expect(area.locator('.animate-spin')).toHaveCount(0);
  });

  // 차례 대기 자체는 단위 시험(DashboardWidgetCard.test — 차례를 쥔 원시 grant)으로 본다. 여기서는 스피너가 한 번도 나타나지 않음을 관찰자로 본다.
  // 강제 반납 E2E 는 더하지 않는다 — 실브라우저에서 렌더 시간을 결정적으로 만들 수 없다.
  test('표(TABLE) 위젯은 다른 차트 응답을 기다리는 동안에도 스피너 없이 표를 보인다 (WD-1113)', async ({ authenticatedPage: page }) => {
    test.setTimeout(60_000);
    // 표 위젯(제목 「위젯 6」 카드) 안에 차례 대기 스피너가 한 번이라도 붙으면 기록한다 — 사라진 뒤 확인하는 toHaveCount(0) 은 순간 스피너를 놓친다
    await page.addInitScript(() => {
      const w = window as unknown as { __tableSpinnerSeen: boolean; __tableSpinnerObserverReady: boolean };
      w.__tableSpinnerSeen = false;
      // 차례 대기 스피너만 본다 — 백그라운드 새로고침 Loader2 도 animate-spin 이라 클래스로 보면 우연히 걸린다
      const AREA = '[data-testid="widget-render-turn-spinner"]';
      // spec 의 card() 와 같은 방법 — data-slot="card" 중 제목 텍스트가 「위젯 6」 인 카드
      const inTableCard = (el: Element) => {
        const c = el.closest('[data-slot="card"]');
        return !!c && Array.from(c.querySelectorAll('*')).some((n) => n.children.length === 0 && n.textContent === '위젯 6');
      };
      const check = (el: Element) => {
        if (el.matches(AREA) && inTableCard(el)) w.__tableSpinnerSeen = true;
      };
      new MutationObserver((list) => {
        for (const m of list) {
          if (m.type === 'attributes' && m.target instanceof Element) check(m.target);
          m.addedNodes.forEach((n) => {
            if (!(n instanceof Element)) return;
            check(n);
            n.querySelectorAll(AREA).forEach(check);
          });
        }
      }).observe(document, { attributes: true, attributeFilter: ['class'], subtree: true, childList: true });
      w.__tableSpinnerObserverReady = true;
    });
    let release: () => void = () => {};
    const released = new Promise<void>((resolve) => {
      release = resolve;
    });
    await setup(page, {
      types: ALL_TYPES,
      // TABLE(위젯 6) 외 차트 응답을 붙잡아 첫 차트도 그리기 전의 상태를 만든다
      onData: async (chartId) => {
        if (chartId !== 6) await released;
      },
    });
    await page.goto('/analytics/dashboards/1');

    const seen = () =>
      page.evaluate(() => (window as unknown as { __tableSpinnerSeen?: boolean }).__tableSpinnerSeen);
    // 관찰자가 실제로 심겼는지 따로 본다 — undefined 로 통과하는 일을 막는다
    expect(await page.evaluate(() => (window as unknown as { __tableSpinnerObserverReady?: boolean }).__tableSpinnerObserverReady)).toBe(true);

    const table = card(page, '위젯 6');
    await expect(table.getByRole('cell', { name: 'c0', exact: true })).toBeVisible({ timeout: 30_000 });
    expect(await seen()).toBe(false);
    // 붙잡은 동안 다른 차트는 아직 하나도 그려지지 않았다
    await expect(page.locator('[data-testid="chart-figure"][data-has-drawing="true"]')).toHaveCount(0);

    release();
    await expect(page.locator('[data-testid="chart-figure"][data-has-drawing="true"]')).toHaveCount(5, { timeout: 30_000 });
    expect(await seen()).toBe(false);
  });

  /** 애니메이션 관찰 결과(브라우저 안 __animReport 가 돌려준다) */
  interface AnimReport {
    barInserted: number;
    lineCount: number;
    lineDashes: number;
    areaCount: number;
    clipRects: number;
    clipWidths: number;
  }

  // recharts 3.7 은 시리즈 종류마다 애니메이션 방식이 달라 「d 가 바뀐 횟수」 하나로는 잡히지 않는다(WD-1113).
  // - 막대(Bar): 프레임마다 d 를 바꾸지 않고 <path> 를 새로 꽂는다 → 꽂힌 막대 path 수와 서로 다른 d 개수가 행 수의 몇 배가 된다
  // - 선(Line): 처음 그릴 때 .recharts-line-curve 의 stroke-dasharray 를 프레임마다 바꾼다 → 끄면 이 속성이 아예 없다
  // - 영역(Area): 애니메이션이 켜졌을 때만 clipPath(animationClipPath-*) 안 사각형으로 드러낸다 → 끄면 이 clipPath 가 없다
  // 막대·선·영역을 위젯 하나씩만 두어(6번째는 TABLE) 종류만으로 위젯이 정해지게 한다 —
  // 떼어진 노드에서 카드 제목을 읽지 않아도 된다. 고정 대기 없이 관찰값이 멈출 때까지 기다린다.
  test('행 100개 넘는 막대·선·영역 차트는 애니메이션 없이 그려진다 (WD-1072)', async ({ authenticatedPage: page }) => {
    test.setTimeout(60_000);
    await page.addInitScript(() => {
      // 관찰 대상 표 — 요소를 종류별로 한 번만 세고(WeakSet), attr 이 있으면 그 속성이 거쳐 간 값도 모은다
      const SPECS = [
        { key: 'bar', selector: '.recharts-bar-rectangle path' },
        { key: 'line', selector: '.recharts-line-curve', attr: 'stroke-dasharray' },
        { key: 'area', selector: '.recharts-area-area' },
        { key: 'clip', selector: 'clipPath[id^="animationClipPath-"] rect', attr: 'width' },
      ];
      const state = SPECS.map((spec) => ({
        ...spec,
        nodes: new WeakSet<Element>(),
        count: 0,
        values: new Set<string>(),
      }));

      /** 요소 하나를 표의 항목별로 기록한다 — 꽂힐 때와 속성이 바뀔 때 모두 부른다 */
      const record = (el: Element) => {
        for (const s of state) {
          if (!el.matches(s.selector)) continue;
          if (!s.nodes.has(el)) {
            s.nodes.add(el);
            s.count += 1;
          }
          const v = s.attr ? el.getAttribute(s.attr) : null;
          if (v) s.values.add(v);
        }
      };
      const all = SPECS.map((s) => s.selector).join(', ');
      const scan = (root: Element) => {
        record(root);
        root.querySelectorAll(all).forEach(record);
      };
      new MutationObserver((list) => {
        for (const m of list) {
          if (m.type === 'attributes' && m.target instanceof Element) record(m.target);
          m.addedNodes.forEach((n) => n instanceof Element && scan(n));
        }
      }).observe(document, {
        attributes: true,
        attributeFilter: ['stroke-dasharray', 'width'],
        subtree: true,
        childList: true,
      });
      const find = (key: string) => state.find((s) => s.key === key)!;
      // 막대 관찰을 새로 시작한다 — 새로고침으로 값이 바뀐 뒤의 갱신만 세기 위해서다
      (window as unknown as { __resetBar: () => void }).__resetBar = () => {
        const bar = find('bar');
        bar.count = 0;
        bar.nodes = new WeakSet<Element>();
      };
      (window as unknown as { __animReport: () => unknown }).__animReport = () => ({
        barInserted: find('bar').count,
        lineCount: find('line').count,
        lineDashes: find('line').values.size,
        areaCount: find('area').count,
        clipRects: find('clip').count,
        clipWidths: find('clip').values.size,
      });
    });
    // 막대는 새로고침 때 값을 바꿔 돌려준다(갱신 애니메이션을 일으킬 입력)
    let shifted = false;
    const shiftedRows = rows.map((row, i) => ({ ...row, v: ((i * 13) % 100) + 1 }));
    await setup(page, {
      types: ALL_TYPES,
      rowsOf: (chartId) => (shifted && chartId === 1 ? shiftedRows : undefined),
    });
    await page.goto('/analytics/dashboards/1');
    await expect(page.locator('[data-testid="chart-figure"][data-has-drawing="true"]')).toHaveCount(5, { timeout: 30_000 });

    const report = () =>
      page.evaluate(() => JSON.stringify((window as unknown as { __animReport: () => unknown }).__animReport()));
    // 관찰값이 멈출 때까지(연속 두 번 읽은 값이 같고 extraCond 를 만족할 때까지) 기다린 뒤 그 값을 돌려준다 — 애니메이션이 있었다면 끝난 뒤다
    const waitStable = async (extraCond?: (r: AnimReport) => boolean) => {
      let prev = '';
      await expect
        .poll(
          async () => {
            const cur = await report();
            const stable = cur === prev && (!extraCond || extraCond(JSON.parse(cur) as AnimReport));
            prev = cur;
            return stable;
          },
          { timeout: 15_000, intervals: [300] },
        )
        .toBe(true);
      return JSON.parse(prev) as AnimReport;
    };
    const first = await waitStable();
    // 첫 그리기 관찰 — 선·영역은 여기서 판정한다(아래 막대 새로고침은 선·영역 값을 바꾸지 않는다)
    // 막대·선·영역이 실제로 그려져 관찰됐다(관찰이 비어 통과하는 일을 막는다)
    expect(first.barInserted, '막대 path 관찰').toBeGreaterThan(0);
    expect(first.lineCount, '선 관찰').toBeGreaterThan(0);
    expect(first.areaCount, '영역 관찰').toBeGreaterThan(0);

    // 막대: 첫 그리기는 다른 위젯 그리기와 겹쳐 프레임 수가 들쭉날쭉하다(켜도 끈 경우와 같은 수가 나온 적 있다).
    // 모두 그려 한가해진 뒤 값을 바꿔 새로고침하면 켜진 경우 갱신 애니메이션이 프레임마다 path 를 새로 꽂는다
    // (관측: 켬 약 3000개 / 끔 238개 — 행 120개). 관찰을 비우고 그 뒤만 센다.
    await page.evaluate(() => (window as unknown as { __resetBar: () => void }).__resetBar());
    shifted = true;
    const refreshed = page.waitForResponse((res) => new URL(res.url()).pathname === '/api/v1/analytics/charts/1/data');
    await card(page, '위젯 1').locator('button[title="새로고침"]').click();
    await refreshed;
    // 갱신된 막대가 실제로 꽂힌 뒤(barInserted > 0)에 멈춘 값을 읽는다
    const after = await waitStable((r) => r.barInserted > 0);

    // 방식마다 따로 판정한다 — soft 라 하나가 실패해도 나머지 판정도 함께 보고된다
    // 막대: 끄면 새 값 한 벌(+재측정 한 벌)만 꽂힌다. 행 수의 3배를 넘으면 프레임마다 다시 꽂은 것이다
    expect.soft(after.barInserted, '막대(위젯 1) 갱신 때 꽂힌 path 수').toBeLessThanOrEqual(rows.length * 3);
    // 선: 끄면 stroke-dasharray 를 쓰지 않는다(켜면 수십 가지 값)
    expect.soft(first.lineDashes, '선(위젯 2) stroke-dasharray 값 개수').toBeLessThanOrEqual(1);
    // 영역: 끄면 애니메이션 clipPath 사각형이 아예 없다(켜면 폭이 수십 번 바뀐다)
    expect.soft(first.clipRects, '영역(위젯 3) animationClipPath 사각형 수').toBe(0);
    expect.soft(first.clipWidths, '영역(위젯 3) animationClipPath 폭 값 개수').toBe(0);
  });
});
