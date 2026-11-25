import type { Page } from '@playwright/test';

import {
  createChart,
  createChartListItem,
  createDashboard,
  createDashboardListItem,
  createSavedQueryList,
  createWidget,
} from '../../factories/analytics.factory';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 이슈 #97: 차트→대시보드 워크플로우 — 차트에서 "대시보드에 추가" 단축 액션 부재
 *
 * 차트 빌더 페이지 헤더에 "대시보드에 추가" 버튼이 있고, 클릭 시 다이얼로그에서
 * 대시보드를 선택하면 위젯으로 추가되어 대시보드 상세로 이동하는지 검증한다.
 */
test.describe('차트 — 대시보드에 추가 단축 액션 (#97)', () => {
  test('차트 빌더 헤더의 "대시보드에 추가" → 다이얼로그 → 위젯 추가 → 대시보드 이동', async ({
    authenticatedPage: page,
  }) => {
    // 기존 차트 빌더 모킹
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, chartType: 'BAR' }));
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/queries',
      createPageResponse(createSavedQueryList(2))
    );
    // 대시보드 목록 — 다이얼로그에서 표시할 항목
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards',
      createPageResponse([
        createDashboardListItem({ id: 10, name: '영업 대시보드' }),
        createDashboardListItem({ id: 11, name: '재무 대시보드' }),
      ])
    );

    // 위젯 추가 API 호출 캡처
    let capturedPayload: { chartId: number; positionX: number; positionY: number; width: number; height: number } | null = null;
    await page.route('**/api/v1/analytics/dashboards/10/widgets', (route) => {
      if (route.request().method() === 'POST') {
        capturedPayload = route.request().postDataJSON();
        // 실제 서버(AddWidgetRequest @Max 240)처럼 상한을 넘으면 거부한다
        if (capturedPayload!.positionY > 240) {
          return route.fulfill({
            status: 400,
            contentType: 'application/json',
            body: JSON.stringify({ message: '행 위치(positionY)는 240 이하여야 합니다.' }),
          });
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createWidget({ id: 999, positionY: 8 })),
        });
      }
      return route.continue();
    });

    // 대시보드 상세 — 추가 후 navigate되는 페이지의 모킹
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/dashboards/10',
      createDashboard({
        id: 10,
        name: '영업 대시보드',
        widgets: [createWidget({ id: 1, positionY: 4, height: 4 })],
      }),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));

    await page.goto('/analytics/charts/1');

    // 헤더에 "대시보드에 추가" 버튼이 표시되어야 한다 (저장된 차트 이므로 chartId 존재)
    const addButton = page.getByRole('button', { name: '대시보드에 추가' });
    await expect(addButton).toBeVisible();
    await addButton.click();

    // 다이얼로그가 열리고 대시보드 목록이 표시되어야 한다
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByText('영업 대시보드')).toBeVisible();
    await expect(page.getByText('재무 대시보드')).toBeVisible();

    // 대시보드 선택 후 추가 버튼 클릭
    await page.getByText('영업 대시보드').click();
    await page.getByRole('button', { name: '추가' }).click();

    // 위젯 추가 API가 올바른 payload로 호출되어야 한다
    await expect.poll(() => capturedPayload).not.toBeNull();
    expect(capturedPayload).toMatchObject({
      chartId: 1,
      positionY: 8, // 기존 위젯(y 4 + h 4) 바로 아래
      width: 6,
      height: 4,
    });

    // 대시보드 상세 페이지로 이동
    await expect(page).toHaveURL(/\/analytics\/dashboards\/10$/, { timeout: 5000 });
  });
});

/**
 * WD-1076: 내 대시보드가 하나도 없으면 「대시보드에 추가」 대화상자에서 이름만 넣어
 * 대시보드를 만들고 차트를 한 번에 넣는다.
 */
test.describe('대시보드가 없을 때 만들고 추가 (WD-1076)', () => {
  const LIST_PATH = '/api/v1/analytics/dashboards';

  /** 차트 빌더 공통 모킹 후 차트 페이지에서 「대시보드에 추가」 대화상자를 연다. */
  async function openDialog(page: Page) {
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, chartType: 'BAR' }));
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse(createSavedQueryList(2)));
    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '대시보드에 추가' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
  }

  // 목록(GET) 응답은 목록 항목 모양, 만들기(POST)·상세(GET /77)는 위젯 없는 전체 모양 —
  // POST 는 실제 서버가 widgets 를 싼 DashboardResponse 를 내려주므로, 씨앗(만든 직후 목록 캐시)이
  // created.widgets.length 를 읽을 수 있다(WD-1106 ③ ruling: 목킹이 계약을 따라간다)
  const createdDashboard = createDashboardListItem({ id: 77, name: '첫 대시보드', widgetCount: 0 });
  const createdDetail = createDashboard({ id: 77, name: '첫 대시보드', widgets: [] });
  const widgetResponse = createWidget({ chartName: 'c' });

  /**
   * 만들기 전 빈 목록·만든 뒤 새 대시보드를 돌려주는 목록 라우트.
   * failListAfterCreate 면 만든 뒤 목록 GET 을 전부 500 으로 돌려 재조회 실패를 재현한다 —
   * 만든 결과가 씨앗만으로 목록에 남는지(WD-1106 ③)를 볼 때 쓴다.
   */
  async function mockCreatableList(page: Page, options?: { failListAfterCreate?: boolean }) {
    const state = { created: false, listGetsAfterCreate: 0 };
    await page.route(
      (url) => url.pathname === LIST_PATH,
      async (route) => {
        if (route.request().method() === 'POST') {
          state.created = true;
          return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(createdDetail) });
        }
        if (state.created) {
          state.listGetsAfterCreate++;
          if (options?.failListAfterCreate) {
            return route.fulfill({
              status: 500,
              contentType: 'application/json',
              body: JSON.stringify({ message: '서버 오류' }),
            });
          }
        }
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createPageResponse(state.created ? [createdDashboard] : [])),
        });
      },
    );
    return state;
  }

  test('이름을 넣고 만들고 추가하면 대시보드를 만들고 차트를 넣어 이동한다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', LIST_PATH, createPageResponse([]));
    const create = await mockApi(page, 'POST', LIST_PATH, createdDetail, { status: 201, capture: true });
    const widget = await mockApi(page, 'POST', `${LIST_PATH}/77/widgets`, widgetResponse, { capture: true });
    await mockApi(page, 'GET', `${LIST_PATH}/77`, createdDetail);
    await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));

    await openDialog(page);
    await expect(page.getByText('아직 대시보드가 없습니다.')).toBeVisible();
    await expect(page.getByText('새 대시보드를 만들어 이 차트를 넣으세요.')).toBeVisible();
    await page.getByLabel('새 대시보드 이름').fill('  첫 대시보드  ');
    await page.getByRole('button', { name: '만들고 추가' }).click();

    await expect(page).toHaveURL(/\/analytics\/dashboards\/77$/);
    expect(create.requests[0].payload).toEqual({ name: '첫 대시보드', isShared: false });
    expect(widget.requests[0].payload).toMatchObject({
      chartId: 1,
      positionX: 0,
      positionY: 0,
      width: 6,
      height: 4,
    });
    await expect(page.getByText("'첫 대시보드' 대시보드를 만들고 차트를 추가했습니다.")).toBeVisible();
  });

  test('공백만이면 만들고 추가가 꺼진다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', LIST_PATH, createPageResponse([]));
    const create = await mockApi(page, 'POST', LIST_PATH, createdDetail, { status: 201, capture: true });
    await openDialog(page);
    await page.getByLabel('새 대시보드 이름').fill('   ');
    await expect(page.getByRole('button', { name: '만들고 추가' })).toBeDisabled();
    expect(create.requests).toHaveLength(0);
  });

  test('이름이 중복이면 입력 아래 서버 문구를 보이고 위젯을 넣지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', LIST_PATH, createPageResponse([]));
    await mockApi(
      page,
      'POST',
      LIST_PATH,
      { code: 'DUPLICATE_NAME', message: '이미 같은 이름의 대시보드가 있습니다.' },
      { status: 409 },
    );
    const widget = await mockApi(page, 'POST', `${LIST_PATH}/77/widgets`, widgetResponse, { capture: true });
    await openDialog(page);
    const input = page.getByLabel('새 대시보드 이름');
    await input.fill('중복');
    await page.getByRole('button', { name: '만들고 추가' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('alert')).toHaveText('이미 같은 이름의 대시보드가 있습니다.');
    await expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(widget.requests).toHaveLength(0);

    await input.fill('중복2');
    await expect(dialog.getByRole('alert')).toHaveCount(0);
  });

  test('만들기는 됐는데 위젯 추가가 실패하면 대화상자에 새 대시보드가 보인다', async ({
    authenticatedPage: page,
  }) => {
    // 만들기 전에는 빈 목록, 만든 뒤에는 새 대시보드를 담는다
    await mockCreatableList(page);
    const widget = await mockApi(
      page,
      'POST',
      `${LIST_PATH}/77/widgets`,
      { message: '서버 오류' },
      { status: 500, capture: true },
    );
    // 다시 「추가」하면 대상 대시보드 상세를 읽어 행 위치를 계산한다
    await mockApi(page, 'GET', `${LIST_PATH}/77`, createdDetail);

    await openDialog(page);
    await page.getByLabel('새 대시보드 이름').fill('첫 대시보드');
    await page.getByRole('button', { name: '만들고 추가' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    const item = dialog.getByRole('button', { name: '첫 대시보드' });
    await expect(item).toBeVisible();
    // 새 대시보드가 미리 선택되어 바로 「추가」를 다시 누를 수 있다
    await expect(item).toHaveAttribute('aria-pressed', 'true');
    await dialog.getByRole('button', { name: '추가', exact: true }).click();
    await expect.poll(() => widget.requests.length).toBe(2);
  });

  test('만들고 추가가 진행 중이면 목록 화면으로 바뀌지 않아 중복 추가를 못 한다', async ({
    authenticatedPage: page,
  }) => {
    const state = await mockCreatableList(page);
    // 위젯 추가 응답을 1초 붙잡아 진행 중 상태를 만든다
    await page.route(`**${LIST_PATH}/77/widgets`, async (route) => {
      await new Promise((r) => setTimeout(r, 1000));
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(widgetResponse),
      });
    });
    await mockApi(page, 'GET', `${LIST_PATH}/77`, createdDetail);
    await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));

    await openDialog(page);
    await page.getByLabel('새 대시보드 이름').fill('첫 대시보드');
    const relisted = page.waitForResponse(
      (r) => new URL(r.url()).pathname === LIST_PATH && r.request().method() === 'GET' && state.created,
    );
    await page.getByRole('button', { name: '만들고 추가' }).click();

    const dialog = page.getByRole('dialog');
    // 만들기 뒤 목록 재조회(GET)가 끝난 뒤에도 만들고 추가 화면이 유지되어야 한다
    await relisted;
    await expect(dialog.getByRole('button', { name: '만들고 추가' })).toBeDisabled();
    await expect(dialog.getByRole('button', { name: '추가', exact: true })).toHaveCount(0);
    await expect(dialog.getByRole('button', { name: '첫 대시보드' })).toHaveCount(0);
    await expect(page).toHaveURL(/\/analytics\/dashboards\/77$/);
  });

  test('남이 공유한 대시보드만 있으면 만들기 입력이 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(
      page,
      'GET',
      LIST_PATH,
      createPageResponse([createDashboardListItem({ id: 5, name: '남의 대시보드', createdBy: 999, isShared: true })]),
    );
    await openDialog(page);
    await expect(page.getByLabel('새 대시보드 이름')).toBeVisible();
  });

  test('목록 조회가 실패하면 만들기 입력이 보이지 않는다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', LIST_PATH, { message: '서버 오류' }, { status: 500 });
    await openDialog(page);
    await expect(page.getByText('대시보드 목록을 불러오지 못했습니다.')).toBeVisible();
    await expect(page.getByLabel('새 대시보드 이름')).toHaveCount(0);
  });

  test('위젯 추가가 빨리 실패하고 목록 재조회도 실패하면 만든 대시보드가 목록에 남고 재시도할 수 있다', async ({
    authenticatedPage: page,
  }) => {
    // 만든 뒤 목록 재조회는 전부 실패한다 — 만든 결과는 씨앗만으로 목록에 남아야 한다
    await mockCreatableList(page, { failListAfterCreate: true });
    // 위젯 POST 는 첫 시도만 500, 재시도부터는 정상 응답
    let widgetAttempts = 0;
    await page.route(`**${LIST_PATH}/77/widgets`, async (route) => {
      widgetAttempts += 1;
      if (widgetAttempts === 1) {
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ message: '서버 오류' }),
        });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(widgetResponse),
      });
    });
    // 재시도 성공 뒤 이동할 상세와, 대시보드 편집기가 읽는 차트 목록
    await mockApi(page, 'GET', `${LIST_PATH}/77`, createdDetail);
    await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));

    await openDialog(page);
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('새 대시보드 이름').fill('첫 대시보드');
    await dialog.getByRole('button', { name: '만들고 추가' }).click();

    // 「만들고 추가」 누른 뒤 대기 없이 단언한다(고정 대기 없음) — 씨앗만으로 항목·선택이 보여야 한다
    const item = dialog.getByRole('button', { name: '첫 대시보드' });
    await expect(item).toBeVisible();
    await expect(item).toHaveAttribute('aria-pressed', 'true');
    // 만들기 입력칸은 없다 — 남은 게 없는 빈 칸에 같은 이름을 넣으면 409 가 난다
    await expect(dialog.getByLabel('새 대시보드 이름')).toHaveCount(0);
    // 안내 문구도 방금 만든 뒤의 부분 실패를 알린다
    await expect(page.getByText('대시보드를 만들었지만 차트 추가에 실패했습니다')).toBeVisible();

    // 그 항목을 다시 골라 「추가」를 누르면 위젯 POST 가 재시도돼 정상 응답으로 성공한다
    await item.click();
    await dialog.getByRole('button', { name: '추가', exact: true }).click();
    await expect(page).toHaveURL(/\/analytics\/dashboards\/77$/);
    expect(widgetAttempts).toBe(2);
  });

  test('만들고 추가 진행 중에는 취소가 꺼지고 Escape 로 닫히지 않으며 이동도 일어나지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await mockCreatableList(page);
    await page.route(`**${LIST_PATH}/77/widgets`, async (route) => {
      await new Promise((r) => setTimeout(r, 800));
      return route.fulfill({
        status: 500,
        contentType: 'application/json',
        body: JSON.stringify({ message: '서버 오류' }),
      });
    });
    await openDialog(page);
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('새 대시보드 이름').fill('첫 대시보드');
    await dialog.getByRole('button', { name: '만들고 추가' }).click();
    await expect(dialog.getByRole('button', { name: '취소' })).toBeDisabled();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeVisible();
    await expect(page).toHaveURL(/\/analytics\/charts\/1$/);
  });

  test('성공하면 대시보드 목록을 다시 읽는다(목록의 위젯 수 갱신)', async ({ authenticatedPage: page }) => {
    const state = await mockCreatableList(page);
    await mockApi(page, 'POST', `${LIST_PATH}/77/widgets`, widgetResponse);
    await mockApi(page, 'GET', `${LIST_PATH}/77`, createdDetail);
    await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));
    await openDialog(page);
    await page.getByLabel('새 대시보드 이름').fill('첫 대시보드');
    await page.getByRole('button', { name: '만들고 추가' }).click();
    await expect(page).toHaveURL(/\/analytics\/dashboards\/77$/);
    // 만들기 직후 재조회 1회 + 위젯 추가 뒤 재조회 — 만든 뒤 목록 GET 이 최소 1회는 있어야 한다
    expect(state.listGetsAfterCreate).toBeGreaterThanOrEqual(1);
    // 씨앗과 재조회가 겹쳐 항목이 두 번 보이지 않는지 — 같은 문서 안에서 뒤로가 다시 열어
    // 목록 캐시(씨앗+재조회 결과)를 그대로 확인한다(새 문서 로드면 캐시가 비어 검사가 무의미하다)
    await page.goBack();
    await expect(page).toHaveURL('/analytics/charts/1');
    await page.getByRole('button', { name: '대시보드에 추가' }).click();
    await expect(
      page.getByRole('dialog').getByRole('button', { name: '첫 대시보드' }),
    ).toHaveCount(1);
  });
});

/**
 * WD-1076: 기존 대시보드에 추가할 때 행 위치는 그 대시보드 위젯들의 맨 아래이며 서버 상한(240)을 넘기지 않는다.
 */
test.describe('대시보드에 추가 — 행 위치 계산 (WD-1076)', () => {
  const LIST_PATH = '/api/v1/analytics/dashboards';

  /** 대상 대시보드 상세(위젯 목록 지정)를 모킹하고 「추가」까지 눌러 보낸 위젯 요청 payload 를 돌려준다. */
  async function addTo(page: Page, widgets: ReturnType<typeof createWidget>[]) {
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, chartType: 'BAR' }));
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse(createSavedQueryList(2)));
    await mockApi(
      page,
      'GET',
      LIST_PATH,
      createPageResponse([createDashboardListItem({ id: 10, name: '영업 대시보드' })]),
    );
    await mockApi(page, 'GET', `${LIST_PATH}/10`, createDashboard({ id: 10, name: '영업 대시보드', widgets }));
    await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));
    let payload: { positionY: number } | null = null;
    await page.route(`**${LIST_PATH}/10/widgets`, (route) => {
      payload = route.request().postDataJSON();
      if (payload!.positionY > 240) {
        return route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ message: '행 위치(positionY)는 240 이하여야 합니다.' }),
        });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createWidget({ id: 999 })),
      });
    });
    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '대시보드에 추가' }).click();
    await page.getByText('영업 대시보드').click();
    await page.getByRole('button', { name: '추가', exact: true }).click();
    await expect.poll(() => payload).not.toBeNull();
    return payload!;
  }

  test('위젯이 없는 대시보드면 0 에 넣는다', async ({ authenticatedPage: page }) => {
    const payload = await addTo(page, []);
    expect(payload.positionY).toBe(0);
    await expect(page).toHaveURL(/\/analytics\/dashboards\/10$/);
  });

  test('여러 위젯 중 가장 아래 끝(y+h)의 최댓값에 넣는다', async ({ authenticatedPage: page }) => {
    const payload = await addTo(page, [
      createWidget({ id: 1, positionY: 0, height: 4 }),
      createWidget({ id: 2, positionY: 4, height: 6 }),
      createWidget({ id: 3, positionY: 2, height: 3 }),
    ]);
    expect(payload.positionY).toBe(10);
  });

  // addTo 안의 expect.poll 은 payload 가 생길 때까지 기다리므로, 요청이 아예 없어야 하는 이 시험은
  // addTo 대신 캡처 배열을 직접 단언한다(기존 「상세 조회 실패」 시험과 같은 직접 모킹 방식).
  test('아래 끝이 240 을 넘으면 위젯을 넣지 않고 안내한다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, chartType: 'BAR' }));
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse(createSavedQueryList(2)));
    await mockApi(
      page,
      'GET',
      LIST_PATH,
      createPageResponse([createDashboardListItem({ id: 10, name: '영업 대시보드' })]),
    );
    await mockApi(
      page,
      'GET',
      `${LIST_PATH}/10`,
      createDashboard({
        id: 10,
        name: '영업 대시보드',
        widgets: [createWidget({ id: 1, positionY: 238, height: 6 })], // 아래 끝 = 244 > 상한 240
      }),
    );
    await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));
    const widget = await mockApi(page, 'POST', `${LIST_PATH}/10/widgets`, createWidget(), { capture: true });

    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '대시보드에 추가' }).click();
    await page.getByText('영업 대시보드').click();
    await page.getByRole('button', { name: '추가', exact: true }).click();

    // 안내 토스트가 흐름의 끝 — 보이는 것을 기다린 뒤 캡처·URL 을 단언한다(고정 대기 없음)
    await expect(
      page.getByText(
        '이 대시보드는 위젯을 둘 자리가 없습니다(마지막 행 244, 상한 240). 대시보드에서 위젯을 옮긴 뒤 다시 시도하세요.',
      ),
    ).toBeVisible();
    // 요청이 하나도 나가지 않았어야 한다 — 자리가 없으면 겹쳐 넣지 않는다(WD-1106 ④)
    expect(widget.requests).toHaveLength(0);
    await expect(page).toHaveURL(/\/analytics\/charts\/1$/);
  });

  // 넘침만 막는다 — 정확히 상한(236+4=240)인 경계는 그대로 요청을 보내 240 으로 넣는다
  test('아래 끝이 정확히 240 이면 요청을 보내 240 으로 넣는다', async ({ authenticatedPage: page }) => {
    const payload = await addTo(page, [createWidget({ id: 1, positionY: 236, height: 4 })]);
    expect(payload.positionY).toBe(240);
    await expect(page).toHaveURL(/\/analytics\/dashboards\/10$/);
  });

  test('대시보드 상세 조회가 실패하면 추가하지 않고 오류를 보인다', async ({ authenticatedPage: page }) => {
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, chartType: 'BAR' }));
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse(createSavedQueryList(2)));
    await mockApi(
      page,
      'GET',
      LIST_PATH,
      createPageResponse([createDashboardListItem({ id: 10, name: '영업 대시보드' })]),
    );
    await mockApi(page, 'GET', `${LIST_PATH}/10`, { message: '서버 오류' }, { status: 500 });
    const widget = await mockApi(page, 'POST', `${LIST_PATH}/10/widgets`, createWidget(), { capture: true });
    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '대시보드에 추가' }).click();
    await page.getByText('영업 대시보드').click();
    await page.getByRole('button', { name: '추가', exact: true }).click();
    await expect(page.getByText('서버 오류').first()).toBeVisible();
    expect(widget.requests).toHaveLength(0);
    await expect(page).toHaveURL(/\/analytics\/charts\/1$/);
  });
});

/**
 * WD-1076 반려: 「추가」는 상세 조회를 기다린 뒤 위젯을 넣는다 — 그 사이 선택을 바꾸거나 다시 눌러도
 * 누른 순간의 대시보드에 한 번만 들어가야 한다.
 */
test.describe('대시보드에 추가 — 진행 중 선택 변경·연속 클릭 (WD-1076)', () => {
  const LIST_PATH = '/api/v1/analytics/dashboards';

  /** 대시보드 둘(10·20)을 모킹한다. 10 의 상세 응답은 release() 를 부를 때까지 붙잡아 둔다. */
  async function setup(page: Page) {
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, chartType: 'BAR' }));
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse(createSavedQueryList(2)));
    await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));
    await mockApi(
      page,
      'GET',
      LIST_PATH,
      createPageResponse([
        createDashboardListItem({ id: 10, name: '영업 대시보드' }),
        createDashboardListItem({ id: 20, name: '재무 대시보드' }),
      ]),
    );
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route(`**${LIST_PATH}/10`, async (route) => {
      await held;
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createDashboard({ id: 10, name: '영업 대시보드', widgets: [] })),
      });
    });
    await mockApi(page, 'GET', `${LIST_PATH}/20`, createDashboard({ id: 20, name: '재무 대시보드', widgets: [] }));
    const widget10 = await mockApi(page, 'POST', `${LIST_PATH}/10/widgets`, createWidget({ id: 901 }), {
      capture: true,
    });
    const widget20 = await mockApi(page, 'POST', `${LIST_PATH}/20/widgets`, createWidget({ id: 902 }), {
      capture: true,
    });
    await page.goto('/analytics/charts/1');
    await page.getByRole('button', { name: '대시보드에 추가' }).click();
    await page.getByRole('button', { name: /영업 대시보드/ }).click();
    return { release: () => release(), widget10, widget20 };
  }

  test('추가 진행 중에는 다른 대시보드를 고를 수 없고 처음 고른 대시보드에 넣고 이동한다', async ({
    authenticatedPage: page,
  }) => {
    const { release, widget10, widget20 } = await setup(page);
    await page.getByRole('button', { name: '추가', exact: true }).click();

    // 상세 조회를 기다리는 동안: 목록 선택·추가·취소가 꺼지고 Escape 로 닫히지 않는다
    const other = page.getByRole('button', { name: /재무 대시보드/ });
    await expect(other).toBeDisabled();
    await expect(page.getByRole('button', { name: '추가', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: '취소' })).toBeDisabled();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('button', { name: /영업 대시보드/ })).toHaveAttribute('aria-pressed', 'true');

    release();
    await expect(page).toHaveURL(/\/analytics\/dashboards\/10$/);
    await expect(page.getByText("'영업 대시보드'에 차트가 추가되었습니다.")).toBeVisible();
    expect(widget10.requests).toHaveLength(1);
    expect(widget20.requests).toHaveLength(0);
  });

  test('추가를 같은 순간 두 번 눌러도 위젯은 한 번만 들어간다', async ({ authenticatedPage: page }) => {
    const { release, widget10 } = await setup(page);
    // 다시 그려지기 전(같은 틱)에 두 번 눌러 버튼 꺼짐이 아닌 동기 잠금을 시험한다
    await page.getByRole('button', { name: '추가', exact: true }).evaluate((el: HTMLElement) => {
      el.click();
      el.click();
    });
    release();
    await expect(page).toHaveURL(/\/analytics\/dashboards\/10$/);
    expect(widget10.requests).toHaveLength(1);
  });
});

/**
 * WD-1106 ①·②: 「추가」 완료 뒤의 이동은 두 가지를 지켜야 한다.
 * ① 요청 도중 사용자가 다른 화면으로 떠났으면, 늦게 온 성공 응답이 그를 다시 대시보드로 끌고 가지 않는다.
 * ② 차트에 미저장 변경이 있으면 그 이동도 「저장하지 않은 변경사항」 확인 창을 지난다 —
 *    대화상자 안 이동은 <a> 클릭 가로채기의 사각지대라 가드를 거치지 않고 곧바로 navigate 했다(WD-1106 ②).
 */
test.describe('대시보드에 추가 — 떠난 뒤 이동 금지·미저장 변경 가드 (WD-1106)', () => {
  const LIST_PATH = '/api/v1/analytics/dashboards';

  /** 차트 빌더 + 대시보드 목록(영업·10)·상세·차트 목록을 한 번에 모킹한다. */
  async function setupBuilder(page: Page) {
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', createChart({ id: 1, chartType: 'BAR' }));
    await mockApi(page, 'GET', '/api/v1/analytics/queries', createPageResponse(createSavedQueryList(2)));
    await mockApi(
      page,
      'GET',
      LIST_PATH,
      createPageResponse([createDashboardListItem({ id: 10, name: '영업 대시보드' })]),
    );
    // 대시보드 상세(추가 뒤 이동 대상)·차트 목록(뒤로갈 화면)을 모킹한다
    await mockApi(page, 'GET', `${LIST_PATH}/10`, createDashboard({ id: 10, name: '영업 대시보드', widgets: [] }));
    await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts',
      createPageResponse([createChartListItem({ id: 1, name: '테스트 차트', chartType: 'BAR' })]),
    );
  }

  /**
   * 「대시보드에 추가」를 열어 대시보드 10 을 고르고 「추가」를 누른다.
   * 이 버튼은 isDirty 때 꺼지므로(#97) 브리프 갱신대로 `.click()` 대신 이벤트를 직접 보낸다 —
   * 안 1 순서에서는 열 때는 아직 깨끗해 켜져 있지만, 두 시험이 같은 여는 방식을 쓴다.
   */
  async function addViaDialog(page: Page) {
    await page
      .getByRole('button', { name: '대시보드에 추가' })
      .dispatchEvent('click', { bubbles: true });
    await page.getByText('영업 대시보드').click();
    await page.getByRole('button', { name: '추가', exact: true }).click();
  }

  test('추가 요청 도중 다른 화면으로 떠났으면 완료 뒤 대시보드로 끌려가지 않는다', async ({
    authenticatedPage: page,
  }) => {
    await setupBuilder(page);

    // 위젯 POST 응답을 1.2초 붙잡는다 — 그 사이 뒤로가기로 떠날 시간을 준다.
    // 전달이 거절돼도 조용히 넘어간다 — 대신 아래 토스트 단언이 응답이 실제로 처리됐는지를 본다.
    const state = { started: false, responded: false };
    await page.route(`**${LIST_PATH}/10/widgets`, async (route) => {
      state.started = true;
      await new Promise((r) => setTimeout(r, 1200));
      try {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(createWidget({ id: 999 })),
        });
      } catch {
        // 전달 거절은 아래 토스트 단언에서 드러난다 — 응답이 안 났으면 성공 토스트도 안 뜬다
      } finally {
        state.responded = true;
      }
    });

    // 뒤로할 화면을 먼저 지나고, 목록의 차트 링크로 같은 문서 안에서 빌더에 들어간다 —
    // 브라우저 뒤로가기가 SPA popstate 로 문서를 갈아끼우지 않고 컴포넌트만 언마운트시켜,
    // 지연 응답이 살아 있는 창(문서) 안에서 이어지는 실제 결함 상황을 재현한다.
    // 모달 오버레이가 바깥 클릭을 막으므로 사이드바가 아니라 브라우저 뒤로가기로 떠남을 재현한다.
    await page.goto('/analytics/charts');
    await page.locator('a[href="/analytics/charts/1"]').click();
    await expect(page).toHaveURL('/analytics/charts/1');
    await addViaDialog(page);

    // POST 가 나간 뒤, 지연 응답이 오기 전에 뒤로가기로 떠난다 (차트가 dirty 가 아니므로 가드가 막지 않는다)
    await expect.poll(() => state.started).toBe(true);
    await page.goBack();
    await expect(page).toHaveURL('/analytics/charts');

    // 늦게 온 성공 응답이 끝난 뒤에도 URL 은 떠난 화면에 남아야 한다 (기존에는 /analytics/dashboards/10 으로 끌려갔다).
    // 같은 토스트가 뜨는지 함께 봐, 응답이 살아 있는 문서에서 실제로 처리됐다는 것도 증명한다 —
    // 이동만 안 했을 뿐 응답 처리가 아예 없었다면 이 시험도 통과해 버리기 때문이다(설계 결정 1: 토스트는 남긴다).
    await expect.poll(() => state.responded, { timeout: 5000 }).toBe(true);
    await expect(page).toHaveURL('/analytics/charts');
    await expect(page.getByText("'영업 대시보드'에 차트가 추가되었습니다.")).toBeVisible();
    await expect(page).toHaveURL('/analytics/charts');
  });

  test('미저장 변경이 있으면 추가 성공 뒤 이동도 「저장하지 않은 변경사항」 확인을 지난다', async ({
    authenticatedPage: page,
  }) => {
    await setupBuilder(page);

    // 위젯 POST 를 캡처하는 route 하나 — 요청 횟수를 단언하기 위해 센다
    const state = { count: 0 };
    await page.route(`**${LIST_PATH}/10/widgets`, (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      state.count += 1;
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(createWidget({ id: 999 })),
      });
    });

    await page.goto('/analytics/charts/1');
    // 대화상자를 평소처럼 연다 — isDirty 상태에서는 헤더 버튼이 꺼져(저장 후 추가, #97) 열 수 없으므로
    // 브리프 갱신대로 `.click()` 대신 이벤트를 직접 보낸다(연 시점은 아직 깨끗하다).
    await page
      .getByRole('button', { name: '대시보드에 추가' })
      .dispatchEvent('click', { bubbles: true });
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.getByText('영업 대시보드').click();

    // 대화상자가 열린 채로 차트 설정을 한 번 바꿔 isDirty 를 켠다 — 모달이 뒷 화면을 접근성 트리에서
    // 숨기고 포인터 클릭도 막으므로, 뒤에 가려진 타입 버튼(aria-label)에 이벤트를 직접 보내 바꾼다
    // (성공 시점에 미저장 변경이 있는 상황을 만드는 것이 목적).
    await page.locator('button[aria-label="선 차트"]').dispatchEvent('click');
    await expect(page.getByText('미저장 변경사항')).toBeVisible();

    await page.getByRole('button', { name: '추가', exact: true }).click();

    // 요청은 정확히 1회, 이동은 가드가 막아 아직 차트 화면에 남는다
    await expect.poll(() => state.count).toBe(1);
    await expect(page).toHaveURL('/analytics/charts/1');
    const guard = page.getByRole('alertdialog');
    await expect(guard.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?')).toBeVisible();

    // 「취소」를 눌러도 이동하지 않고 요청은 그대로 1회여야 한다
    await guard.getByRole('button', { name: '취소' }).click();
    await expect(page).toHaveURL('/analytics/charts/1');
    expect(state.count).toBe(1);

    // 한 확인 창은 한 번만 처리된다 — 「취소」가 대상(pendingTo)을 지우므로 같은 대시보드 대상으로는
    // 다시 확인 창을 띄울 수 없다(「추가」를 다시 눌러야 하는데 그건 위젯 POST 2회가 되어 「정확히 1회」와 어긋난다).
    // 그래서 다시 떠나기를 시도(← 목록)해 확인 창이 또 뜨는지, 「이탈」이 실제로 이동하는지만 본다 —
    // 요청은 시험 처음부터 끝까지 1회여야 한다.
    await page.getByRole('button', { name: '목록으로 돌아가기' }).click();
    const secondGuard = page.getByRole('alertdialog');
    await expect(secondGuard.getByText('저장하지 않은 변경사항이 있습니다. 이탈하시겠습니까?')).toBeVisible();
    await secondGuard.getByRole('button', { name: '이탈' }).click();
    await expect(page).toHaveURL('/analytics/charts');
    expect(state.count).toBe(1);
  });
});
