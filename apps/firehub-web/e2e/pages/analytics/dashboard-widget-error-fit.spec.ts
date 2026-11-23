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
 * 고치기 전의 `WidgetErrorBox` 는 바깥 상자(`h-full overflow-auto`)만 스크롤하고 안내 상자(내용)에는 높이 제한이
 * 없어, 좁고 낮은 위젯에서 문구가 감기면 내용이 보이는 영역보다 높아져 아래쪽(「다시 시도」)이 잘렸다.
 * 이 스펙은 (1) 가로 2칸(격자 최소) 2줄 문구, (2) 높이 2칸 + 긴 문구 두 경우에서
 * 버튼이 스크롤 없이 클립 상자 안에 온전히(32×32px 이상) 보이는지 단언하고,
 * (3) 넓은 6×4 + 짧은 문구에서 안내 상자가 카드 안쪽을 덮지 않고 내용에 맞는 높이로 남는지(높이 회귀),
 * (4) 여백 축소(p-3 → p-1.5)가 버튼 없는 「쿼리 오류」 상자까지 미치지 않는지 단언한다(리뷰 후속 ③),
 * (5) 넘치는 긴 문구가 안쪽 메시지 div 에서 실제로 넘쳐 화살표 키로 스크롤해 다 읽히는지 단언한다(리뷰 후속 ①·④).
 * 측정 방식은 WD-926(`dashboard-widget-refresh.spec.ts` WD-926 테스트)과 같다 —
 * 버튼은 자기에서 위로 올라가며 overflowY(auto|hidden|scroll) 조상과 비교한다.
 * 메시지 영역 단언은 그와 달리 위로 찾지 않는다: role=alert 안의 `role=group` 메시지 div(alert 안
 * `min-h-0 overflow-auto`)를 직접 잰다(리뷰 후속 ①·② — 위로 올라가며 찾으면 alert 밖 스크롤 상자나 `hidden`
 * 조상을 골라 단언이 약해진다). 그 div 의 `scrollHeight <= clientHeight + 1` 로 「2줄 문구도 스크롤 없이 다
 * 보임」을 잡는다 — alert 을 재면 `alert.scrollHeight == alert.clientHeight` 인 공리적 통과가 되어 고치기 전
 * 결함(내용 114px > 보이는 91px)을 잡지 못한다.
 * 완료 기준 ① 「긴 실패 문구는 스크롤로 다 읽을 수 있다」 — 테스트 2 는 문구가 넘치지 않아(실측 40/40) overflowY 가
 * 실제로 스크롤되는 `auto`|`scroll` 인지만 보고(`hidden` 은 잘림만 막을 뿐 사용자가 못 읽으므로 제외 — 리뷰 후속 ①),
 * 넘침과 화살표 스크롤은 테스트 5 가 좁은 위젯의 긴 문구로 실측한다. 스크롤되는 영역은 키보드로 포커스할 수 있어야
 * 화살표로 읽는다(WCAG 2.1.1 — 리뷰 후속 ④).
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

    // 메시지 영역: 카드 안의 `role=group` 메시지 div(alert 안 `min-h-0 overflow-auto`)를 직접 잰다(리뷰 후속 ①·②).
    // 위로 올라가며 찾으면 alert 밖 스크롤 상자나 `hidden` 조상을 골라 단언이 약해지고, alert 자체를 재면
    // alert.scrollHeight == alert.clientHeight 인 공리적 통과가 되어 고치기 전 결함(내용 114px > 보이는 91px)을
    // 잡지 못한다. 카드에서 따로 찾는 것은 `alert.querySelector` 로 뽑으면 `alert.contains(area)` 가 늘 참이라
    // 「안내 상자 안에 있음」 단언이 공리가 되기 때문이다 — role 을 고르는 또 다른 까닭은 마크업이 한 겹 더 생겨도
    // 같은 요소를 재고 키보드 단언(리뷰 후속 ④)과 측정이 한 요소로 붙기 때문이다. 못 찾으면 null 로 둔다.
    const alert = el.closest('[role="alert"]');
    const cardRoot = el.closest('[data-slot="card"]') ?? document.documentElement;
    const area: Element | null = cardRoot.querySelector('[role="group"]');

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
      // 안쪽 메시지 div 를 찾았는지 — 못 찾으면 뒤 단언이 공리적으로 통과하지 않게 여기서 먼저 깨진다
      msgAreaFound: !!area,
      // 못 찾은 쪽의 기본값은 「넘치지 않음」 방향(내용은 아주 크게, 보이는 것은 0) — 단언 순서와 상관없이 깨진다
      msgScrollHeight: area ? area.scrollHeight : Number.MAX_SAFE_INTEGER,
      msgClientHeight: area ? area.clientHeight : 0,
      // 긴 문구·키보드 단언용 — 메시지 영역이 안내 상자 안에 있는지, 실제로 스크롤되는 overflowY 인지,
      // 키보드로 포커스할 수 있고 접근 이름이 있는지
      msgInAlert: !!alert && !!area && alert.contains(area),
      msgOverflowY: area ? getComputedStyle(area).overflowY : 'none',
      msgTabIndex: area ? area.getAttribute('tabindex') : null,
      msgLabel: area ? area.getAttribute('aria-label') : null,
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
    // 2줄 문구도 스크롤 없이 다 보임 — alert 안 첫 <p> 의 부모(안쪽 메시지 div)를 직접 잰 값(리뷰 후속 ② — 잰 요소에 맞춘 단언)
    expect(m.msgAreaFound, `안쪽 메시지 div 를 찾음 — ${JSON.stringify(m)}`).toBe(true);
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
    // 완료 기준 ① 「긴 실패 문구는 스크롤로 다 읽을 수 있다」 — 안쪽 메시지 div 를 직접 잰다(리뷰 후속 ①·②).
    // 메시지 영역이 안내 상자 안에 있어야 상자와 함께 읽고, overflowY 가 실제로 스크롤되는 auto|scroll 이어야
    // 문구가 늘어나면 스스로 스크롤한다(hidden 은 잘림만 막을 뿐 사용자가 못 읽으므로 제외 — 리뷰 후속 ①).
    // 문구는 짧아 넘치지 않아(실측 40/40) 실제 오버플로(`>`) 단언은 넣지 않는다.
    expect(m.msgAreaFound, `안쪽 메시지 div 를 찾음 — ${JSON.stringify(m)}`).toBe(true);
    expect(m.msgInAlert, `긴 실패 문구를 읽을 수 있음 — 메시지 영역이 안내 상자 안에 있음 — ${JSON.stringify(m)}`).toBe(
      true,
    );
    expect(
      ['auto', 'scroll'],
      `긴 실패 문구를 읽을 수 있음 — 메시지 영역 overflowY 가 실제로 스크롤됨(hidden 제외) — ${JSON.stringify(m)}`,
    ).toContain(m.msgOverflowY);

    // 리뷰 후속 ④ — 스크롤되는 메시지 영역을 키보드로 포커스할 수 있고 접근 이름이 읽힌다(WCAG 2.1.1)
    expect(m.msgTabIndex, `메시지 영역 키보드 접근 가능(tabIndex=0) — ${JSON.stringify(m)}`).toBe('0');
    expect(m.msgLabel, `메시지 영역 접근 이름 — ${JSON.stringify(m)}`).toBeTruthy();
    const msgArea = card(page, '위젯 A').getByRole('group', { name: '오류 안내' });
    await msgArea.focus();
    await expect(msgArea).toBeFocused();
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

  test('여백 축소는 「다시 시도」가 있는 조회 실패 상자에만 적용된다 (WD-959 리뷰 후속 ③)', async ({
    authenticatedPage: page,
  }) => {
    // 조회 실패(버튼 있는 상자)와 쿼리 오류(버튼 없는 상자)를 한 화면에 두고 여백을 비교한다 —
    // 여백 축소는 짧은 위젯에 버튼까지 넣기 위한 것이라, 버튼이 없는 「쿼리 오류」 상자는 원래 여백을 쓴다.
    await setupDashboard(page, [
      createWidget({ id: 11, chartId: 1, chartName: '위젯 A', chartType: 'TABLE', width: 6, height: 4 }),
      createWidget({ id: 12, chartId: 2, chartName: '위젯 B', chartType: 'TABLE', positionX: 6, width: 6, height: 4 }),
    ]);
    await routeChartData(page, 1, 500, { message: '위젯 A 원본 조회 실패' });
    await routeChartData(page, 2, 200, {
      chart: chartB,
      queryResult: createQueryResult({ columns: ['cat', 'v'], rows: [], totalRows: 0, error: 'division by zero' }),
    });

    await page.goto('/analytics/dashboards/1');
    const paddingOf = (name: string) => card(page, name).getByRole('alert').evaluate((el) => getComputedStyle(el).padding);

    const retryPad = await paddingOf('위젯 A');
    expect(retryPad, `조회 실패 상자(버튼 있음)는 좁은 여백 p-1.5 — ${retryPad}`).toBe('6px');

    const queryPad = await paddingOf('위젯 B');
    expect(queryPad, `쿼리 오류 상자(버튼 없음)는 원래 여백 p-3 — ${queryPad}`).toBe('12px');

    // 리뷰 후속 ④ — 버튼 없는 「쿼리 오류」 상자도 메시지 영역이 키보드로 닿고 접근 이름이 있어야 한다
    // (공용 상자라 어느 분기든 같은 마크업을 쓴다 — 긴 SQL 사유가 넘칠 가능성은 여기가 더 크다)
    const a11yOf = (name: string) =>
      card(page, name)
        .getByRole('group', { name: '오류 안내' })
        .evaluate((el) => ({ tabindex: el.getAttribute('tabindex'), label: el.getAttribute('aria-label') }));
    const retryA11y = await a11yOf('위젯 A');
    expect(retryA11y, `조회 실패 상자 메시지 영역 키보드 접근 — ${JSON.stringify(retryA11y)}`).toEqual({
      tabindex: '0',
      label: '오류 안내',
    });
    const queryA11y = await a11yOf('위젯 B');
    expect(queryA11y, `쿼리 오류 상자 메시지 영역 키보드 접근 — ${JSON.stringify(queryA11y)}`).toEqual({
      tabindex: '0',
      label: '오류 안내',
    });
  });

  test('넘치는 긴 실패 문구는 안쪽 메시지 div 에서 화살표 키로 스크롤해 다 읽을 수 있다 (WD-959 리뷰 후속 ①·④)', async ({
    authenticatedPage: page,
  }) => {
    // 가로 2칸(좁은 위젯) + 긴 문구 — 그래야 문구가 실제로 넘쳐 스크롤·키보드 접근 단언이 의미를 갖는다
    // (6칸이라면 폭이 남아 문구가 넘치지 않아 단언이 공리적으로 통과한다)
    await setupDashboard(page, [
      createWidget({ id: 11, chartId: 1, chartName: '위젯 A', chartType: 'TABLE', width: 2, height: 2 }),
      createWidget({ id: 12, chartId: 2, chartName: '위젯 B', chartType: 'TABLE', positionX: 6, width: 6, height: 4 }),
    ]);
    await routeChartData(page, 1, 500, { message: LONG_MESSAGE });
    await routeChartData(page, 2, 200, rowsBody(chartB, 'b-1'));

    await page.goto('/analytics/dashboards/1');
    const retry = card(page, '위젯 A').getByRole('button', { name: '다시 시도' });
    await expect(retry).toBeVisible();

    // 버튼은 넘치는 문구가 있어도 스크롤 없이 온전해야 한다(WD-959 본 수정 회귀)
    const m = await measureRetryFit(retry);
    expectRetryFit(m);

    expect(m.msgAreaFound, `안쪽 메시지 div 를 찾음 — ${JSON.stringify(m)}`).toBe(true);
    // 실제로 넘친다 — 넘치지 않으면 아래 스크롤·화살표 단언이 공리적으로 통과한다
    expect(m.msgScrollHeight, `긴 문구가 실제로 넘침 — ${JSON.stringify(m)}`).toBeGreaterThan(m.msgClientHeight);
    expect(
      ['auto', 'scroll'],
      `넘치는 영역 overflowY 가 실제로 스크롤됨(hidden 제외) — ${JSON.stringify(m)}`,
    ).toContain(m.msgOverflowY);
    expect(m.msgTabIndex, `메시지 영역 키보드 접근 가능(tabIndex=0) — ${JSON.stringify(m)}`).toBe('0');

    // 화살표 키로 실제로 스크롤한다 — 키보드로 긴 문구를 다 읽을 수 있음(완료 기준 ①·WCAG 2.1.1)
    const msgArea = card(page, '위젯 A').getByRole('group', { name: '오류 안내' });
    await msgArea.focus();
    await expect(msgArea).toBeFocused();
    await msgArea.press('ArrowDown');
    await expect
      .poll(async () => msgArea.evaluate((el) => el.scrollTop), { message: '화살표 키로 아래로 스크롤' })
      .toBeGreaterThan(0);
  });
});
