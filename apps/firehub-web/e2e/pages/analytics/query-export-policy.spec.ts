import type { Page } from '@playwright/test';

import {
  createChart,
  createDashboard,
  createQueryResult,
  createSavedQuery,
  createWidget,
} from '../../factories/analytics.factory';
import { setupNewChartBuilderMocks, setupQueryEditorMocks, setupQueryListMocks } from '../../fixtures/analytics.fixture';
import { createPageResponse, mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 분석 화면 내보내기 정책(S4) — 쿼리 결과 내보내기는 화면 rows 가 아니라 실행 기록 id(runId)로 서버가 다시 판정·실행한다.
 * 조회자 기준 exportAllowed !== true 면 주 버튼(쿼리 편집기 「내보내기」·차트 빌더 「다운로드」)은 비활성+툴팁,
 * 보조(대시보드 PDF)는 숨긴다(스펙 §5-4).
 */
const BLOCKED = '보안 등급 정책상 이 데이터는 내보낼 수 없습니다.';
const RUN_MISSING = '실행 기록이 없습니다. 쿼리를 다시 실행한 뒤 내보내세요.';
const RESULT = createQueryResult({ columns: ['v'], rows: [{ v: 'x' }], totalRows: 1 });

/** 저장 쿼리 편집기 진입 → 「실행」(애드혹 /execute) → 결과 표 대기 */
async function runInEditor(page: Page) {
  await page.goto('/analytics/queries/1');
  await expect(page.getByText('테스트 쿼리')).toBeVisible();
  await page.getByRole('button', { name: '실행' }).click();
  await expect(page.getByRole('columnheader', { name: 'v' })).toBeVisible();
}

test.describe('쿼리 결과 내보내기 — 실행 기록 재실행', () => {
  test('내보내기는 rows 가 아니라 runId 경로로 형식만 보낸다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);
    const exec = await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/execute',
      { ...RESULT, exportAllowed: true, runId: 'run-1' },
      { capture: true },
    );
    const exp = await mockApi(page, 'POST', '/api/v1/analytics/queries/runs/run-1/export', 'v\nx\n', {
      capture: true,
    });
    await runInEditor(page);
    // 편집기는 애드혹 /execute 를 쓴다(실행 기록이 남는 경로)
    expect((await exec.waitForRequest()).payload).toMatchObject({ maxRows: 1000 });

    await page.getByRole('button', { name: '내보내기' }).click();
    await page.getByRole('menuitem', { name: 'CSV로 내보내기' }).click();
    expect((await exp.waitForRequest()).payload).toEqual({ format: 'CSV' });
    await expect(page.getByText('파일이 다운로드되었습니다.')).toBeVisible();
  });

  test('exportAllowed=false 면 내보내기는 비활성 + 정책 툴팁', async ({ authenticatedPage: page }) => {
    await setupQueryEditorMocks(page, 1);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/execute', { ...RESULT, exportAllowed: false, runId: 'run-2' });
    await runInEditor(page);
    const btn = page.getByRole('button', { name: '내보내기' });
    await expect(btn).toBeDisabled();
    // 래퍼 접근 이름 = '동작 이름 — 사유'(스크린리더가 무엇이 막혔는지 먼저 듣는다)
    await page.getByRole('group', { name: `내보내기 — ${BLOCKED}`, exact: true }).hover();
    await expect(page.getByRole('tooltip')).toHaveText(BLOCKED);
  });

  test('exportAllowed 가 없으면(구버전 응답) 비활성 — fail-closed', async ({ authenticatedPage: page }) => {
    await setupQueryEditorMocks(page, 1);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/execute', {
      ...RESULT,
      exportAllowed: undefined,
      runId: 'run-3',
    });
    await runInEditor(page);
    await expect(page.getByRole('button', { name: '내보내기' })).toBeDisabled();
  });

  test('실행 기록이 만료·없음(404 QUERY_RUN_NOT_FOUND)이면 서버 문구를 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupQueryEditorMocks(page, 1);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/execute', { ...RESULT, exportAllowed: true, runId: 'run-4' });
    const message = '실행 기록을 찾을 수 없습니다. 쿼리를 다시 실행한 뒤 내보내세요.';
    // blob 요청의 오류 본문은 Blob 으로 온다 — 비동기로 풀어 서버 message 를 보여야 한다
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/runs/run-4/export',
      { status: 404, code: 'QUERY_RUN_NOT_FOUND', message },
      { status: 404 },
    );
    await runInEditor(page);
    await page.getByRole('button', { name: '내보내기' }).click();
    await page.getByRole('menuitem', { name: 'CSV로 내보내기' }).click();
    await expect(page.getByText(message)).toBeVisible();
  });

  /** 쿼리 목록 → 행 「실행」(저장 쿼리 /{id}/execute) → 편집기로 이동해 결과 표 대기. runId 만 바꿔 끼운다. */
  async function runFromList(page: Page, runId: string | null) {
    await setupQueryListMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/analytics/queries/1', createSavedQuery({ id: 1 }));
    await mockApi(page, 'GET', '/api/v1/analytics/queries/schema', { tables: [] });
    await mockApi(page, 'GET', '/api/v1/analytics/queries/folders', []);
    await mockApi(page, 'POST', '/api/v1/analytics/queries/1/execute', { ...RESULT, exportAllowed: true, runId });
    await page.goto('/analytics/queries');
    const row = page.getByRole('row').filter({ hasText: '저장 쿼리 1' });
    await row.hover();
    await row.getByLabel('실행').click();
    await expect(page).toHaveURL('/analytics/queries/1');
    await expect(page.getByRole('columnheader', { name: 'v' })).toBeVisible();
  }

  test('목록 「실행」(저장 쿼리 실행) 결과도 runId 로 내보낸다 — 파일 이름은 서버가 정한다', async ({
    authenticatedPage: page,
  }) => {
    // 저장 쿼리 /{id}/execute 도 실행 기록을 남겨 runId 를 싣는다(code-review 4)
    await runFromList(page, 'run-5');
    const serverName = 'query_result_20261010_상위1000행.csv';
    const disposition =
      `attachment; filename="query_result_20261010_.csv"; filename*=UTF-8''${encodeURIComponent(serverName)}`;
    let exportPayload: unknown = null;
    await page.route(
      (url) => url.pathname === '/api/v1/analytics/queries/runs/run-5/export',
      (route) => {
        exportPayload = route.request().postDataJSON();
        return route.fulfill({
          status: 200,
          contentType: 'text/csv',
          headers: { 'Content-Disposition': disposition },
          body: 'v\nx\n',
        });
      },
    );
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: '내보내기' }).click();
    await page.getByRole('menuitem', { name: 'CSV로 내보내기' }).click();
    // 화면 결과(1행, 잘림 없음)로 만든 이름이 아니라 서버가 정한 한글 이름(filename*)으로 저장된다
    expect((await download).suggestedFilename()).toBe(serverName);
    expect(exportPayload).toEqual({ format: 'CSV' });
    await expect(page.getByText('파일이 다운로드되었습니다.')).toBeVisible();
  });

  test('runId 가 없는 결과(구버전 응답)는 내보내기 비활성 + 다시 실행 안내', async ({ authenticatedPage: page }) => {
    await runFromList(page, null);
    await expect(page.getByRole('button', { name: '내보내기' })).toBeDisabled();
    await page.getByRole('group', { name: `내보내기 — ${RUN_MISSING}`, exact: true }).hover();
    await expect(page.getByRole('tooltip')).toHaveText(RUN_MISSING);
  });
});

test.describe('차트 빌더·대시보드 내보내기 정책', () => {
  /** 새 차트 빌더 → 저장 쿼리 선택 → 실행. 실행 응답의 exportAllowed 만 바꾼다. */
  async function runChartQuery(page: Page, exportAllowed: boolean | undefined) {
    await setupNewChartBuilderMocks(page);
    await mockApi(
      page,
      'POST',
      '/api/v1/analytics/queries/1/execute',
      createQueryResult({
        columns: ['category', 'amount'],
        rows: [
          { category: 'A', amount: 10 },
          { category: 'B', amount: 20 },
        ],
        totalRows: 2,
        exportAllowed,
        runId: null,
      }),
    );
    await page.goto('/analytics/charts/new');
    await page.getByRole('combobox').click();
    await page.getByRole('option', { name: '저장 쿼리 1' }).click();
    await page.getByRole('button', { name: '쿼리 실행' }).click();
    await expect(page.getByText('2개 컬럼, 2개 행 로드됨')).toBeVisible();
  }

  test('차트 빌더 다운로드는 exportAllowed=false 면 비활성 + 정책 툴팁', async ({ authenticatedPage: page }) => {
    await runChartQuery(page, false);
    await expect(page.getByRole('button', { name: '차트 다운로드' })).toBeDisabled();
    await page.getByRole('group', { name: `차트 다운로드 — ${BLOCKED}`, exact: true }).hover();
    await expect(page.getByRole('tooltip')).toHaveText(BLOCKED);
  });

  test('차트 빌더 다운로드는 exportAllowed=true 면 메뉴가 열린다(양성 대조)', async ({ authenticatedPage: page }) => {
    await runChartQuery(page, true);
    await page.getByRole('button', { name: '차트 다운로드' }).click();
    await expect(page.getByRole('menuitem', { name: 'PNG 이미지로 저장' })).toBeVisible();
  });

  /** 위젯 2개 대시보드 — 두 번째 위젯의 chartData 만 바꿔 끼운다. */
  async function dashboard(page: Page, second: { exportAllowed?: boolean; denied?: boolean }) {
    const w1 = createWidget({ id: 1, chartId: 1, chartName: '출동 건수', width: 6, height: 4 });
    const w2 = createWidget({ id: 2, chartId: 2, chartName: '월별 인건비', positionX: 6, width: 6, height: 4 });
    const c1 = createChart({ id: 1, name: '출동 건수', config: { xAxis: 'm', yAxis: ['v'] } });
    const c2 = createChart({ id: 2, name: '월별 인건비', config: { xAxis: 'm', yAxis: ['v'] } });
    await mockApi(page, 'GET', '/api/v1/analytics/dashboards/1', createDashboard({ id: 1, widgets: [w1, w2] }));
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1', c1);
    await mockApi(page, 'GET', '/api/v1/analytics/charts/2', c2);
    const qr = createQueryResult({ columns: ['m', 'v'], rows: [{ m: '1월', v: 3 }], totalRows: 1 });
    await mockApi(page, 'GET', '/api/v1/analytics/charts/1/data', { chart: c1, queryResult: qr, exportAllowed: true });
    const data2 = await mockApi(
      page,
      'GET',
      '/api/v1/analytics/charts/2/data',
      { chart: c2, queryResult: qr, ...second },
      { capture: true },
    );
    await mockApi(page, 'GET', '/api/v1/analytics/charts', createPageResponse([]));
    await page.goto('/analytics/dashboards/1');
    await expect(page.getByRole('heading', { name: '테스트 대시보드' })).toBeVisible();
    // 두 번째 위젯 데이터가 실제로 도착한 뒤 판정한다(그 전엔 판정 대상이 아님)
    await data2.waitForRequest();
  }

  test('대시보드 PDF 는 exportAllowed=false 위젯이 하나라도 있으면 숨긴다', async ({ authenticatedPage: page }) => {
    await dashboard(page, { exportAllowed: false });
    // 위젯이 렌더된 뒤에도(데이터 반영 후) 버튼이 없어야 한다
    await expect(page.locator('[data-slot="card"]').filter({ hasText: '월별 인건비' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'PDF로 내보내기' })).toHaveCount(0);
    // 공유 링크 등 다른 툴바 버튼은 그대로
    await expect(page.getByRole('button', { name: '공유 링크 복사' })).toBeVisible();
  });

  test('대시보드 PDF 는 denied 위젯이 있어도 숨긴다', async ({ authenticatedPage: page }) => {
    // exportAllowed 는 true 로 두어 denied 분기만으로 숨겨지는지 단독 검증한다
    await dashboard(page, { denied: true, exportAllowed: true });
    await expect(page.getByTestId('widget-denied')).toBeVisible();
    await expect(page.getByRole('button', { name: 'PDF로 내보내기' })).toHaveCount(0);
  });

  test('대시보드 PDF 는 위젯이 모두 허용이면 보인다(양성 대조)', async ({ authenticatedPage: page }) => {
    await dashboard(page, { exportAllowed: true });
    await expect(page.getByRole('button', { name: 'PDF로 내보내기' })).toBeVisible();
  });
});
