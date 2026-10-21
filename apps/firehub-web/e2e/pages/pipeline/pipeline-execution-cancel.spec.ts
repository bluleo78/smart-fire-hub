/**
 * 파이프라인 실행 취소 E2E 테스트(WD-153)
 *
 * 진행 중(PENDING/RUNNING) 실행을 사용자가 멈출 수 있어야 한다. 검증 범위:
 * - 「실행 취소」 버튼은 진행 중 실행에만 보이고(상세 헤더 + 실행 이력 행), 끝난 실행(COMPLETED/FAILED/CANCELLED)에는 없다
 * - 취소 요청이 이미 기록된 RUNNING 실행은 「취소 요청됨」 비활성 버튼
 * - 확인 창을 닫으면 API 를 부르지 않고, 확정하면 POST .../executions/{id}/cancel 을 보낸다
 * - 취소 후 재조회한 상태(CANCELLED)가 「취소됨」 배지·취소 정보로 반영되고, 409 는 서버 사유를 보여 준다
 * - 취소된 스텝·취소로 건너뛴 스텝은 실패(오류 상세)가 아니라 취소 사유로 보인다
 */

import type { Page, Route } from '@playwright/test';

import type { ExecutionDetailResponse, PipelineExecutionResponse } from '../../../src/types/pipeline';
import {
  createExecution,
  createExecutionDetail,
  createStepExecution,
} from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

const PIPELINE_ID = 1;
const EXEC_ID = 7;
const DETAIL_PATH = `/api/v1/pipelines/${PIPELINE_ID}/executions/${EXEC_ID}`;
const CANCEL_PATH = `${DETAIL_PATH}/cancel`;
const LIST_PATH = `/api/v1/pipelines/${PIPELINE_ID}/executions`;

/** 진행 중 실행 상세 — 첫 스텝 실행 중, 둘째 스텝 대기 */
function runningDetail(overrides?: Partial<ExecutionDetailResponse>): ExecutionDetailResponse {
  return createExecutionDetail({
    id: EXEC_ID,
    pipelineId: PIPELINE_ID,
    status: 'RUNNING',
    completedAt: null,
    stepExecutions: [
      createStepExecution({ id: 1, stepId: 1, status: 'RUNNING', completedAt: null, outputRows: null, log: null }),
      createStepExecution({ id: 2, stepId: 2, stepName: '데이터 변환', status: 'PENDING', startedAt: null, completedAt: null, outputRows: null, log: null }),
    ],
    cancelRequestedAt: null,
    cancelRequestedBy: null,
    ...overrides,
  });
}

/** 취소 완료된 실행 상세 — 진행 중이던 스텝은 CANCELLED, 실행 전 스텝은 취소 사유가 담긴 SKIPPED */
function cancelledDetail(): ExecutionDetailResponse {
  return createExecutionDetail({
    id: EXEC_ID,
    pipelineId: PIPELINE_ID,
    status: 'CANCELLED',
    completedAt: '2024-01-01T00:00:40Z',
    errorMessage: '사용자가 실행을 취소했습니다.',
    cancelRequestedAt: '2024-01-01T00:00:30Z',
    cancelRequestedBy: '홍길동',
    stepExecutions: [
      createStepExecution({
        id: 1,
        stepId: 1,
        status: 'CANCELLED',
        outputRows: 120,
        log: null,
        errorMessage: '사용자가 실행을 취소했습니다. 취소 전까지 120행 적재됨',
        completedAt: '2024-01-01T00:00:40Z',
      }),
      createStepExecution({
        id: 2,
        stepId: 2,
        stepName: '데이터 변환',
        status: 'SKIPPED',
        outputRows: null,
        log: null,
        startedAt: null,
        completedAt: null,
        errorMessage: '실행이 취소되어 이 스텝은 실행하지 않았습니다.',
      }),
    ],
  });
}

/** 실행 상세 GET 을 상태에 따라 돌려주는 모킹 — 테스트 중 응답을 바꿔도 폴링과 경합하지 않도록 함수로 받는다. */
async function mockDetail(page: Page, get: () => ExecutionDetailResponse) {
  await page.route(
    (url) => url.pathname === DETAIL_PATH,
    (route: Route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(get()) });
    },
  );
}

/** 실행 이력 목록 GET 모킹(상태 함수형) */
async function mockList(page: Page, get: () => PipelineExecutionResponse[]) {
  await page.route(
    (url) => url.pathname === LIST_PATH,
    (route: Route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(get()) });
    },
  );
}

/**
 * 실행 상세 헤더의 취소 버튼 — 상세 화면에는 실행 이력 행 버튼(aria-label 「실행 #N 실행 취소」)이 없고, 확인 창이 열리면
 * 바깥은 aria-hidden 이므로 exact 이름으로 헤더 버튼 하나만 잡힌다.
 */
const headerCancelButton = (page: Page) => page.getByRole('button', { name: '실행 취소', exact: true });

test.describe('파이프라인 실행 취소 (WD-153)', () => {
  test('진행 중 실행 상세에서 확인 후 취소하면 cancel API 를 부르고 「취소됨」으로 바뀐다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, PIPELINE_ID);
    let cancelled = false;
    await mockDetail(page, () => (cancelled ? cancelledDetail() : runningDetail()));
    const cancelCapture = await mockApi(
      page,
      'POST',
      CANCEL_PATH,
      { executionId: EXEC_ID, status: 'CANCELLED', cancelRequestedAt: '2024-01-01T00:00:30Z' },
      { status: 202, capture: true },
    );
    // 취소 요청이 닿으면 다음 재조회부터 CANCELLED 를 돌려준다(서버 상태 전이 흉내)
    page.on('request', (req) => {
      if (req.method() === 'POST' && new URL(req.url()).pathname === CANCEL_PATH) cancelled = true;
    });

    await page.goto(`/pipelines/${PIPELINE_ID}/executions/${EXEC_ID}`);
    await expect(page.getByText('실행 정보')).toBeVisible();
    // 진행 중이므로 취소 버튼이 보이고 재실행은 없다
    await expect(headerCancelButton(page)).toBeEnabled();
    await expect(page.getByRole('button', { name: '같은 입력으로 다시 실행' })).toHaveCount(0);

    await headerCancelButton(page).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByRole('heading', { name: '실행을 취소할까요?' })).toBeVisible();
    // 협력적 취소의 의미(진행 중 작업은 마침·남은 스텝 미실행·REPLACE/APPEND 결과)를 알린다
    await expect(dialog).toContainText('진행 중인 API 호출이나 SQL 은 끝까지 마친 뒤 멈추고');
    await expect(dialog).toContainText('교체(REPLACE)');
    await expect(dialog).toContainText('추가(APPEND)');

    await dialog.getByRole('button', { name: '실행 취소', exact: true }).click();

    // 요청: 본문 없는 POST /pipelines/1/executions/7/cancel
    const req = await cancelCapture.waitForRequest();
    expect(req.url.pathname).toBe(CANCEL_PATH);
    expect(cancelCapture.requests).toHaveLength(1);

    await expect(page.locator('[data-sonner-toast]').filter({ hasText: '실행을 취소했습니다.' })).toBeVisible();
    // 재조회 결과 반영: 「취소됨」 배지, 취소 정보(누가·언제), 취소 버튼 사라지고 재실행 등장
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '취소됨' }).first()).toBeVisible();
    await expect(page.getByTestId('execution-cancel-info')).toContainText('홍길동님이');
    await expect(page.getByTestId('execution-cancel-info')).toContainText('취소했습니다.');
    await expect(headerCancelButton(page)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '같은 입력으로 다시 실행' })).toBeVisible();
    // 취소는 실패가 아니다 — 실행 단위 실패 안내·오류 상세가 나오지 않는다
    await expect(page.getByText(/파이프라인 실행 자체가 실패했습니다/)).toHaveCount(0);
    await expect(page.getByText('오류 상세')).toHaveCount(0);
    // 스텝 현황에 취소된 스텝 수가 보인다
    await expect(page.getByText('취소됨 1')).toBeVisible();
  });

  test('확인 창에서 「계속 실행」을 누르면 취소 API 를 부르지 않는다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, PIPELINE_ID);
    await mockDetail(page, () => runningDetail());
    const cancelCapture = await mockApi(page, 'POST', CANCEL_PATH, {}, { status: 202, capture: true });

    await page.goto(`/pipelines/${PIPELINE_ID}/executions/${EXEC_ID}`);
    await headerCancelButton(page).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: '계속 실행', exact: true }).click();
    await expect(dialog).toHaveCount(0);

    // 요청이 나갈 시간을 준 뒤에도 0건이어야 한다
    await page.waitForTimeout(500);
    expect(cancelCapture.requests).toHaveLength(0);
    await expect(headerCancelButton(page)).toBeEnabled();
  });

  test('RUNNING 응답(요청 기록)이면 「취소 요청됨」 비활성으로 바뀌고, 폴링으로 CANCELLED 전이를 잡는다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, PIPELINE_ID);
    // 0: 취소 전 / 1: 요청 기록(RUNNING + cancelRequestedAt) / 2: 실행기가 멈춤(CANCELLED)
    let phase = 0;
    let getsAfterRequest = 0;
    await mockDetail(page, () => {
      if (phase === 0) return runningDetail();
      getsAfterRequest += 1;
      // 첫 재조회는 아직 RUNNING(요청만 기록), 그 다음 폴링부터 CANCELLED
      if (getsAfterRequest === 1) {
        return runningDetail({ cancelRequestedAt: '2024-01-01T00:00:30Z', cancelRequestedBy: '홍길동' });
      }
      return cancelledDetail();
    });
    await page.route(
      (url) => url.pathname === CANCEL_PATH,
      (route) => {
        phase = 1;
        return route.fulfill({
          status: 202,
          contentType: 'application/json',
          body: JSON.stringify({ executionId: EXEC_ID, status: 'RUNNING', cancelRequestedAt: '2024-01-01T00:00:30Z' }),
        });
      },
    );

    await page.goto(`/pipelines/${PIPELINE_ID}/executions/${EXEC_ID}`);
    await headerCancelButton(page).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '실행 취소', exact: true }).click();

    await expect(
      page.locator('[data-sonner-toast]').filter({ hasText: '취소를 요청했습니다. 진행 중인 작업이 끝나는 대로 멈춥니다.' }),
    ).toBeVisible();
    // 요청 기록 상태: 버튼은 「취소 요청됨」 비활성, 패널은 "취소 요청됨" 안내
    const requestedButton = page.getByRole('button', { name: '취소 요청됨', exact: true });
    await expect(requestedButton).toBeDisabled();
    await expect(page.getByTestId('execution-cancel-info')).toContainText('홍길동님이');
    await expect(page.getByTestId('execution-cancel-info')).toContainText('진행 중인 작업이 끝나는 대로 멈춥니다.');

    // 상세 폴링(3초)으로 CANCELLED 를 받으면 버튼이 사라지고 「취소됨」이 된다
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '취소됨' }).first()).toBeVisible({ timeout: 8000 });
    await expect(requestedButton).toHaveCount(0);
    await expect(page.getByTestId('execution-cancel-info')).toContainText('취소했습니다.');
  });

  test('이미 끝난 실행(409)이면 서버 사유를 보여 준다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, PIPELINE_ID);
    await mockDetail(page, () => runningDetail());
    await mockApi(
      page,
      'POST',
      CANCEL_PATH,
      { status: 409, error: 'Conflict', message: '이미 끝난 실행은 취소할 수 없습니다' },
      { status: 409 },
    );

    await page.goto(`/pipelines/${PIPELINE_ID}/executions/${EXEC_ID}`);
    await headerCancelButton(page).click();
    await page.getByRole('alertdialog').getByRole('button', { name: '실행 취소', exact: true }).click();

    await expect(page.locator('[data-sonner-toast]').filter({ hasText: '이미 끝난 실행은 취소할 수 없습니다' })).toBeVisible();
  });

  test('상세: PENDING 은 취소 버튼이 보이고, 끝난 실행(COMPLETED/FAILED/CANCELLED)에는 없다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, PIPELINE_ID);
    let status: ExecutionDetailResponse['status'] = 'PENDING';
    await mockDetail(page, () =>
      status === 'PENDING' || status === 'RUNNING'
        ? runningDetail({ status, startedAt: null })
        : createExecutionDetail({ id: EXEC_ID, pipelineId: PIPELINE_ID, status }),
    );

    await page.goto(`/pipelines/${PIPELINE_ID}/executions/${EXEC_ID}`);
    await expect(headerCancelButton(page)).toBeEnabled();

    for (const finished of ['COMPLETED', 'FAILED', 'CANCELLED'] as const) {
      status = finished;
      await page.reload();
      await expect(page.getByText('실행 정보')).toBeVisible();
      // 재실행이 보이면 상세가 렌더된 것 — 그 시점에 취소 버튼은 없어야 한다
      await expect(page.getByRole('button', { name: '같은 입력으로 다시 실행' })).toBeVisible();
      await expect(headerCancelButton(page)).toHaveCount(0);
      await expect(page.getByRole('button', { name: '취소 요청됨' })).toHaveCount(0);
    }
  });

  test('마지막 점검 지점 뒤 취소(COMPLETED + cancelRequestedAt)는 취소 정보 없이 원래 결과를 보여 준다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, PIPELINE_ID);
    await mockDetail(page, () =>
      createExecutionDetail({
        id: EXEC_ID,
        pipelineId: PIPELINE_ID,
        status: 'COMPLETED',
        cancelRequestedAt: '2024-01-01T00:00:55Z',
        cancelRequestedBy: '홍길동',
      }),
    );

    await page.goto(`/pipelines/${PIPELINE_ID}/executions/${EXEC_ID}`);
    await expect(page.getByRole('button', { name: '같은 입력으로 다시 실행' })).toBeVisible();
    await expect(page.getByTestId('execution-cancel-info')).toHaveCount(0);
    await expect(headerCancelButton(page)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '취소 요청됨' })).toHaveCount(0);
  });

  test('실행 이력 목록: 진행 중 행에만 취소 버튼, 요청 기록 행은 「취소 요청됨」 비활성, 확정 시 해당 실행 id 로 요청', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, PIPELINE_ID);
    let cancelledId: number | null = null;
    const rows = (): PipelineExecutionResponse[] => [
      createExecution({ id: 11, status: cancelledId === 11 ? 'CANCELLED' : 'RUNNING', completedAt: null }),
      createExecution({ id: 12, status: 'PENDING', startedAt: null, completedAt: null }),
      createExecution({ id: 13, status: 'RUNNING', completedAt: null, cancelRequestedAt: '2024-01-01T00:00:30Z' }),
      createExecution({ id: 14, status: 'COMPLETED' }),
      createExecution({ id: 15, status: 'FAILED' }),
      createExecution({ id: 16, status: 'CANCELLED', cancelRequestedAt: '2024-01-01T00:00:30Z' }),
    ];
    await mockList(page, rows);
    const cancelPaths: string[] = [];
    await page.route(
      (url) => /\/api\/v1\/pipelines\/1\/executions\/\d+\/cancel$/.test(url.pathname),
      (route) => {
        const path = new URL(route.request().url()).pathname;
        cancelPaths.push(`${route.request().method()} ${path}`);
        cancelledId = Number(path.split('/').at(-2));
        return route.fulfill({
          status: 202,
          contentType: 'application/json',
          body: JSON.stringify({ executionId: cancelledId, status: 'CANCELLED', cancelRequestedAt: '2024-01-01T00:00:30Z' }),
        });
      },
    );

    await page.goto(`/pipelines/${PIPELINE_ID}`);
    await page.getByRole('tab', { name: '실행 이력' }).click();

    const row = (id: number) => page.getByRole('row').filter({ has: page.getByRole('cell', { name: String(id), exact: true }) });
    await expect(row(11).getByRole('button', { name: '실행 #11 실행 취소' })).toBeEnabled();
    await expect(row(12).getByRole('button', { name: '실행 #12 실행 취소' })).toBeEnabled();
    await expect(row(13).getByRole('button', { name: '실행 #13 취소 요청됨' })).toBeDisabled();
    for (const id of [14, 15, 16]) {
      await expect(row(id).getByRole('button')).toHaveCount(0);
    }

    // 확인 창을 닫으면 요청 없음 + 행 클릭(상세 이동)으로 번지지 않는다
    await row(11).getByRole('button', { name: '실행 #11 실행 취소' }).click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog).toContainText('실행 #11');
    await dialog.getByRole('button', { name: '계속 실행', exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await expect(page).toHaveURL(new RegExp(`/pipelines/${PIPELINE_ID}$`));
    expect(cancelPaths).toHaveLength(0);

    // 확정하면 그 행의 실행 id 로 POST, 목록 재조회 결과가 「취소됨」으로 반영되고 버튼이 사라진다
    await row(11).getByRole('button', { name: '실행 #11 실행 취소' }).click();
    await dialog.getByRole('button', { name: '실행 취소', exact: true }).click();
    await expect.poll(() => cancelPaths).toEqual([`POST /api/v1/pipelines/1/executions/11/cancel`]);
    await expect(row(11).locator('[data-slot="badge"]').filter({ hasText: '취소됨' })).toBeVisible();
    await expect(row(11).getByRole('button')).toHaveCount(0);
    // 확정 클릭이 행 이동으로 번지지 않았다
    await expect(page).toHaveURL(new RegExp(`/pipelines/${PIPELINE_ID}$`));
  });

  test('취소된 스텝은 오류가 아니라 취소 내용으로, 취소로 건너뛴 스텝은 취소 사유로 보인다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, PIPELINE_ID);
    await mockDetail(page, () => cancelledDetail());

    await page.goto(`/pipelines/${PIPELINE_ID}/executions/${EXEC_ID}`);
    await expect(page.getByText('실행 정보')).toBeVisible();

    // 진행 중이던 스텝(CANCELLED): 「취소 내용」 + 서버 문구(적재 행 수), 오류 상세·빨간 원문 없음
    await page.locator('.react-flow__node').filter({ has: page.getByText('데이터 추출', { exact: true }) }).click();
    const cancelledNotice = page.getByTestId('cancelled-step-notice');
    await expect(cancelledNotice).toContainText('취소 내용');
    await expect(cancelledNotice).toContainText('사용자가 실행을 취소했습니다. 취소 전까지 120행 적재됨');
    await expect(page.getByText('오류 상세')).toHaveCount(0);
    await expect(page.getByText(/스텝 실행 중 오류가 발생했습니다/)).toHaveCount(0);

    // 실행 전이던 스텝(SKIPPED + 취소 문구): 고정 "선행 스텝 실패" 안내 대신 취소 사유
    await page.locator('.react-flow__node').filter({ has: page.getByText('데이터 변환', { exact: true }) }).click();
    const skippedNotice = page.getByTestId('skipped-step-notice');
    await expect(skippedNotice).toContainText('실행이 취소되어 이 스텝은 실행하지 않았습니다.');
    await expect(skippedNotice).not.toContainText('선행 스텝이 실패');
  });

  test('원문이 가려진 조회자도 취소된 실행의 건너뜀 스텝은 취소 사유로 본다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, PIPELINE_ID);
    const masked = '이 스텝의 상세 오류는 관련 데이터를 볼 수 있는 사용자에게만 표시됩니다.';
    const detail = cancelledDetail();
    detail.stepExecutions[1] = { ...detail.stepExecutions[1], errorMessage: masked, errorMasked: true };
    await mockDetail(page, () => detail);

    await page.goto(`/pipelines/${PIPELINE_ID}/executions/${EXEC_ID}`);
    await page.locator('.react-flow__node').filter({ has: page.getByText('데이터 변환', { exact: true }) }).click();
    const skippedNotice = page.getByTestId('skipped-step-notice');
    await expect(skippedNotice).toContainText('실행이 취소되어 이 스텝은 실행하지 않았습니다.');
    await expect(skippedNotice).not.toContainText('선행 스텝이 실패');
  });
});
