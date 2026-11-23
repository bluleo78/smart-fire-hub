import type { Locator, Page, Route } from '@playwright/test';

import type { Dashboard } from '../../../src/types/analytics';
import { createChart, createDashboard, createQueryResult, createWidget } from '../../factories/analytics.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 위젯 조회 실패 안내 「다시 시도」 잘림 회귀 테스트 (WD-959)
 *
 * WD-959 — 2×2 위젯에서 오류 문구가 두 줄로 감겨 안내 상자(보이는 91px)의 내용(114px)이 더 높아 「다시 시도」 6px 가
 * 잘렸다는 실측(2026-11-23, 1280×720).
 *
 * `WidgetErrorBox` 는 바깥 상자(`h-full overflow-auto`)만 스크롤하고 안내 상자(내용)는 높이 제한이 없어,
 * 좁고 낮은 위젯에서 문구가 감기면 내용이 보이는 영역보다 높아져 아래쪽(「다시 시도」)이 잘렸다.
 * 이 스펙은 (1) 가로 2칸(격자 최소) 2줄 문구, (2) 높이 2칸 + 긴 문구 두 경우에서
 * 버튼이 스크롤 없이 클립 상자 안에 온전히(32×32px 이상) 보이는지 단언하고,
 * (3) 넓은 6×4 + 짧은 문구에서 안내 상자가 카드 안쪽을 덮지 않고 내용에 맞는 높이로 남는지도 단언한다(높이 회귀).
 * 측정 방식은 WD-926(`dashboard-widget-refresh.spec.ts` WD-926 테스트)과 같다 —
 * 자기에서 위로 올라가며 overflowY(auto|hidden|scroll) 조상과 비교한다.
 * 메시지 영역 단언은 그와 달리 alert 경계에서 멈추지 않는다: role=alert 안의 첫 번째 `<p>`(오류 문구 줄)에서
 * 시작 요소 자신을 포함해 위로 올라가며 만난 가장 가까운 스크롤 상자(못 찾으면 `document.documentElement`)의
 * `scrollHeight <= clientHeight + 1` 로 「2줄 문구도 스크롤 없이 다 보임」을 잡는다 — alert 안에서 멈추면
 * `alert.scrollHeight == alert.clientHeight` 인 공리적 통과가 되어 고치기 전 결함(내용 114px > 보이는 91px)을 잡지 못한다.
 * (4) 완료 기준 ① 「긴 실패 문구는 스크롤로 다 읽을 수 있다」 — 테스트 2 에서 메시지 영역이 안내 상자(role=alert) 안에 있고(밖이면 상자와 함께 못 읽는다) overflowY 가 스크롤형(auto|scroll|hidden)이라 문구가 늘어나면 스스로 스크롤함을 단언한다(실측 scrollHeight 40 == clientHeight 40 으로 넘치지는 않아 실제 오버플로(`>`) 단언은 제외).
 */

const chartA = createChart({ id: 1, name: '위젯 A', chartType: 'TABLE', config: { xAxis: '', yAxis: [] } });
const chartB = createChart({ id: 2, name: '위젯 B', chartType: 'TABLE', config: { xAxis: '', yAxis: [] } });

/** 정상 데이터 응답 — TABLE 차트이므로 카드에 행이 그대로 보인다 */
const rowsBody = (chart: typeof chartA, cat: string) => ({
  chart,
  queryResult: createQueryResult({ columns: ['cat', 'v'], rows: [{ cat, v: 1 }], totalRows: 1 }),
});

/** 높이 2칸 위젯에 오는 긴 실패 문구(동시 실행 상한 안내) — 문구 자체는 스크롤이 맞다, 버튼만 온전해야 한다 */
const LONG_MESSAGE =
  '동시에 실행 중인 쿼리가 많습니다 — 실행 중인 쿼리가 끝난 뒤 다시 시도하세요. 잠시 후 같은 화면에서 다시 시도하면 됩니다.';

/** 대시보드 메타·차트 메타·차트 목록을 모킹한다 — 데이터 라우트는 각 테스트가 따로 건다 */
async function setupDashboard(page: Page, widgets: Dashboard['widgets']) {
  const dashboard = createDashboard({ id: 1, widgets });
  await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', dashboard);
  await mockApi(page, 'GET', '/api/v1/analytics/charts/1', chartA);
  await mockApi(page, 'GET', '/api/v1/analytics/charts/2', chartB);
  await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));
}

/** 위젯 단건 데이터 응답 라우트 — 지정한 HTTP 상태·본문으로 항상 응답한다 */
async function routeChartData(page: Page, chartId: number, status: number, body: unknown) {
  await page.route(
    (url) => url.pathname === `/api/v1/analytics/charts/${chartId}/data`,
    (route: Route) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) }),
  );
}

/** 위젯 카드(차트 이름으로 식별) */
const card = (page: Page, name: string) =>
  page.locator('[data-slot="card"]').filter({ has: page.getByText(name, { exact: true }) });

/**
 * 「다시 시도」 버튼과 그 버튼을 잘라 낼 수 있는 가장 가까운 overflow 조상(clips)을 함께 잰다(WD-926 측정 방식).
 * 조상을 못 찾으면 `document.documentElement` 를 쓴다. 실패 메시지에 그대로 넣을 수 있도록 측정값 전체를 반환한다.
 */
async function measureRetryFit(retry: Locator) {
  return retry.evaluate((el) => {
    /** 스크롤(또는 잘림)을 일으킬 수 있는 overflowY 인지 본다 — WD-926 과 같은 셋 */
    const scrolls = (node: Element) => ['auto', 'hidden', 'scroll'].includes(getComputedStyle(node).overflowY);

    // 클립 상자: 버튼에서 위로 올라가며 가장 가까운 overflow 조상
    let clip: Element | null = el.parentElement;
    while (clip && !scrolls(clip)) clip = clip.parentElement;
    const clipEl = clip ?? document.documentElement;

    // 메시지 영역: role=alert 안의 첫 번째 <p>(오류 문구 줄)에서 위로 올라가며 overflow 조상을 찾는다 — 시작 요소 자신도
    // 검사하고, alert 경계에서 멈추지 않는다. alert 안에서 멈추면 alert.scrollHeight == alert.clientHeight 인 공리적
    // 통과가 되어 완료 기준(2줄 문구 스크롤 없이 다 보임)을 잡지 못하므로, 바깥 스크롤 상자까지 올라가야
    // 고치기 전(내용 114px > 보이는 91px)에는 FAIL, 고친 뒤(안내 상자 max-h-full)에는 PASS 가 된다.
    // 조상을 못 찾으면 document.documentElement 를 쓴다.
    const alert = el.closest('[role="alert"]');
    let msg: Element | null = alert?.querySelector('p') ?? alert;
    let msgArea: Element | null = null;
    while (msg) {
      if (scrolls(msg)) {
        msgArea = msg;
        break;
      }
      msg = msg.parentElement;
    }
    const area = msgArea ?? document.documentElement;

    const b = el.getBoundingClientRect();
    const c = clipEl.getBoundingClientRect();
    return {
      width: b.width,
      height: b.height,
      bottom: b.bottom,
      right: b.right,
      clipBottom: c.bottom,
      clipRight: c.right,
      clipScrollTop: clipEl.scrollTop,
      // 잘린 만큼 — 「아래로 잘리지 않음」 실패가 얼마만큼 잘렸는지 한눈에 보이게 한다
      cut: b.bottom - c.bottom,
      msgScrollHeight: area.scrollHeight,
      msgClientHeight: area.clientHeight,
      // 긴 문구 단언용(F1) — 메시지 영역이 안내 상자 안에 있는지, overflowY 가 스크롤형인지
      msgInAlert: !!alert && alert.contains(area),
      msgOverflowY: getComputedStyle(area).overflowY,
    };
  });
}

/**
 * 세 테스트가 같은 기준으로 보는 「다시 시도」 단언 다섯 개 — 높이·너비 32px 이상(WD-926), 클립 상자 안, 스크롤할 필요 없음.
 * 실패 메시지에 측정값 JSON 을 그대로 넣는다(어디가 잘렸는지 한눈에 보기 위해).
 */
function expectRetryFit(m: Awaited<ReturnType<typeof measureRetryFit>>) {
  const sizes = JSON.stringify(m);
  expect(m.height, `버튼 높이 32px 이상 — ${sizes}`).toBeGreaterThanOrEqual(32);
  expect(m.width, `버튼 너비 32px 이상 — ${sizes}`).toBeGreaterThanOrEqual(32);
  expect(m.bottom, `안내 상자 아래로 잘리지 않음 — ${sizes}`).toBeLessThanOrEqual(m.clipBottom);
  expect(m.right, `안내 상자 오른쪽으로 잘리지 않음 — ${sizes}`).toBeLessThanOrEqual(m.clipRight);
  expect(m.clipScrollTop, `버튼을 보려고 스크롤할 필요 없음 — ${sizes}`).toBe(0);
}

test.describe('대시보드 위젯 — 조회 실패 안내 「다시 시도」 잘림 (WD-959)', () => {
  test('가로 2칸 위젯의 조회 실패 안내에서 「다시 시도」가 스크롤 없이 온전히 보인다 (WD-959)', async ({
    authenticatedPage: page,
  }) => {
    // 가로 2칸(격자 최소) 2×2 — 오류 문구가 두 줄로 감겨 안내 상자 내용(114px)이 보이는 영역(91px)보다 높다
    await setupDashboard(page, [
      createWidget({ id: 11, chartId: 1, chartName: '위젯 A', chartType: 'TABLE', width: 2, height: 2 }),
      createWidget({ id: 12, chartId: 2, chartName: '위젯 B', chartType: 'TABLE', positionX: 6, width: 6, height: 4 }),
    ]);
    await routeChartData(page, 1, 500, { message: '위젯 A 원본 조회 실패' });
    await routeChartData(page, 2, 200, rowsBody(chartB, 'b-1'));

    await page.goto('/analytics/dashboards/1');
    const retry = card(page, '위젯 A').getByRole('button', { name: '다시 시도' });
    await expect(retry).toBeVisible();

    const m = await measureRetryFit(retry);
    expectRetryFit(m);
    // 2줄 문구도 스크롤 없이 다 보임 — alert 안 첫 <p>에서 위로 올라가 만난 바깥 스크롤 상자 기준(alert 경계에서 멈추지 않음)
    expect(m.msgScrollHeight, `메시지 영역 스크롤 없이 다 보임 — ${JSON.stringify(m)}`).toBeLessThanOrEqual(
      m.msgClientHeight + 1,
    );
  });

  test('높이 2칸 위젯에 긴 실패 문구가 와도 「다시 시도」가 스크롤 없이 온전히 보인다 (WD-959)', async ({
    authenticatedPage: page,
  }) => {
    // 높이 2칸 + 긴 문구 — 문구는 스스로 스크롤해도 되지만 버튼은 클립 상자 안에 온전해야 한다
    await setupDashboard(page, [
      createWidget({ id: 11, chartId: 1, chartName: '위젯 A', chartType: 'TABLE', width: 6, height: 2 }),
      createWidget({ id: 12, chartId: 2, chartName: '위젯 B', chartType: 'TABLE', positionX: 6, width: 6, height: 4 }),
    ]);
    await routeChartData(page, 1, 500, { message: LONG_MESSAGE });
    await routeChartData(page, 2, 200, rowsBody(chartB, 'b-1'));

    await page.goto('/analytics/dashboards/1');
    const retry = card(page, '위젯 A').getByRole('button', { name: '다시 시도' });
    await expect(retry).toBeVisible();

    const m = await measureRetryFit(retry);
    expectRetryFit(m);
    // 완료 기준 ① 「긴 실패 문구는 스크롤로 다 읽을 수 있다」 — 메시지 영역이 안내 상자 안에 있어야 상자와 함께 읽고,
    // overflowY 가 스크롤형이어야 문구가 늘어나면 스스로 스크롤한다(실측 40/40 로 넘치지는 않아 실제 오버플로 단언은 제외)
    expect(m.msgInAlert, `긴 실패 문구를 읽을 수 있음 — 메시지 영역이 안내 상자 안에 있음 — ${JSON.stringify(m)}`).toBe(
      true,
    );
    expect(
      ['auto', 'scroll', 'hidden'],
      `긴 실패 문구를 읽을 수 있음 — 메시지 영역 overflowY 가 스크롤형 — ${JSON.stringify(m)}`,
    ).toContain(m.msgOverflowY);
  });

  test('넓은 위젯의 짧은 실패 문구는 안내 상자가 카드 안쪽을 덮지 않고 내용에 맞는 높이다 (WD-959)', async ({
    authenticatedPage: page,
  }) => {
    // 6×4 넓은 위젯 + 짧은 문구 — 상자가 카드 안쪽 전체를 덮지 않고 내용에 맞는 높이로 남아야 한다(높이 회귀 확인)
    await setupDashboard(page, [
      createWidget({ id: 11, chartId: 1, chartName: '위젯 A', chartType: 'TABLE', width: 6, height: 4 }),
      createWidget({ id: 12, chartId: 2, chartName: '위젯 B', chartType: 'TABLE', positionX: 6, width: 6, height: 4 }),
    ]);
    await routeChartData(page, 1, 500, { message: '위젯 A 원본 조회 실패' });
    await routeChartData(page, 2, 200, rowsBody(chartB, 'b-1'));

    await page.goto('/analytics/dashboards/1');
    const cardA = card(page, '위젯 A');
    const retry = cardA.getByRole('button', { name: '다시 시도' });
    await expect(retry).toBeVisible();

    // 버튼 단언은 다른 두 테스트와 같다 — 짧은 문구에서도 온전히 보이고 스크롤할 필요 없어야 한다
    const m = await measureRetryFit(retry);
    expectRetryFit(m);

    // 안내 상자(role=alert) 높이와 그 스크롤 조상(clip) 높이를 잰다 — 짧은 문구에 맞춰 상자가 늘어나지 않고
    // 카드 안쪽 전체를 덮지 않아야 한다(max-h-full 회귀가 없음을 확인)
    const heights = await cardA.getByRole('alert').evaluate((el) => {
      /** 스크롤(또는 잘림)을 일으킬 수 있는 overflowY 인지 본다 — 위 단언과 같은 셋 */
      const scrolls = (node: Element) => ['auto', 'hidden', 'scroll'].includes(getComputedStyle(node).overflowY);
      let clip: Element | null = el.parentElement;
      while (clip && !scrolls(clip)) clip = clip.parentElement;
      const clipEl = clip ?? document.documentElement;
      return {
        alertHeight: el.getBoundingClientRect().height,
        clipHeight: clipEl.getBoundingClientRect().height,
      };
    });
    const sizeStr = JSON.stringify(heights);
    expect(heights.alertHeight, `안내 상자가 카드 안쪽을 덮지 않고 내용에 맞는 높이다 — ${sizeStr}`).toBeLessThan(
      heights.clipHeight,
    );
  });
});
