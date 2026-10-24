import type { Page } from '@playwright/test';

import { createColumn, createDataset, createDatasetDetail } from '../../factories/dataset.factory';
import {
  createExecution,
  createExecutionDetail,
  createPipelineDetail,
  createStep,
  createStepExecution,
  createTrigger,
} from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, PIPELINE_ALL_PERMISSIONS, setupPipelineMocks } from '../../fixtures/pipeline.fixture';

/**
 * 파이프라인 상세 — 권한별 쓰기 진입점 노출 (WD-219)
 *
 * 조회 권한만 있는 사용자(pipeline:read·trigger:read·dataset:read)에게도 헤더 「실행」「수정」, 트리거 탭 「트리거 추가」·⋯ 메뉴
 * (편집·활성화/비활성화·삭제)가 보였고, 누르면 「이 작업을 할 권한이 없습니다 (필요 권한: trigger:write)」 토스트를 받았다.
 * 서버가 요구하는 권한(PipelineController·TriggerController 의 @RequirePermission)과 같은 기준으로 보이는지 조합별로 확인한다:
 * 수정·증분 재생성 예약 pipeline:write, 실행·재실행·실행 취소 pipeline:execute, 트리거 추가·편집·켜고 끄기 trigger:write, 트리거 삭제 trigger:delete.
 *
 * 부재 단언이 공허해지지 않도록 모든 경우가 같은 준비 절차를 탄다(dataset-list-permissions.spec 선례): ① 권한 응답을 기다리고
 * ② 화면 내용(파이프라인 이름·트리거 이름·실행 행)이 그려진 것을 확인한 뒤 ③ 한 프레임 쉬고 판정한다.
 * 권한이 있는 대조군이 같은 절차에서 버튼을 보이므로 절차 자체가 버튼을 가리지 않음이 증명된다.
 */

const READ_ONLY = ['pipeline:read', 'trigger:read', 'dataset:read'];
const PIPELINE_ID = 1;
const RUNNING_EXEC_ID = 7;
const FAILED_EXEC_ID = 8;
const SQL = 'SELECT code FROM src WHERE _updated_at >= {{last_run_at}}';
const OUTPUT_DATASET_ID = 10;

/** 응답 처리 후 한 프레임 뒤(리렌더 반영) — 「없다」 단언 직전에 둔다. */
async function nextFrame(page: Page) {
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));
}

/** 권한 응답이 도착한 뒤 경로로 이동한다 — 권한이 오기 전의 "숨김"을 결과로 착각하지 않기 위함. */
async function gotoWithPermissions(page: Page, path: string) {
  const permissionsLoaded = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/v1/auth/me/permissions');
  await page.goto(path);
  await permissionsLoaded;
}

/**
 * 파이프라인 상세 모킹 — 증분 SQL 스텝 하나(재생성 예약 컨트롤 대상), 진행 중·실패 실행 하나씩, 트리거 하나.
 * 진행 중 실행이 있어야 실행 이력 행의 「실행 취소」를, 증분 스텝이 있어야 「처음부터 다시 만들기」를 판정할 수 있다.
 */
async function setupDetailMocks(page: Page, permissions: string[]) {
  const step = createStep({
    id: 5,
    scriptContent: SQL,
    outputDatasetId: OUTPUT_DATASET_ID,
    outputDatasetName: '출력 데이터셋',
    lastRunAt: '2026-09-19T01:00:00Z',
    fullRebuildPending: false,
    fullRebuildMode: 'REBUILD_OUTPUT',
  });
  await mockApi(page, 'GET', `/api/v1/pipelines/${PIPELINE_ID}`, createPipelineDetail({ id: PIPELINE_ID, steps: [step] }));
  await mockApi(page, 'GET', `/api/v1/pipelines/${PIPELINE_ID}/executions`, [
    createExecution({ id: RUNNING_EXEC_ID, pipelineId: PIPELINE_ID, status: 'RUNNING', completedAt: null }),
    createExecution({ id: FAILED_EXEC_ID, pipelineId: PIPELINE_ID, status: 'FAILED', completedAt: '2024-01-01T00:02:00Z' }),
  ]);
  await mockApi(page, 'GET', `/api/v1/pipelines/${PIPELINE_ID}/executions/${RUNNING_EXEC_ID}`, createExecutionDetail({
    id: RUNNING_EXEC_ID,
    pipelineId: PIPELINE_ID,
    status: 'RUNNING',
    completedAt: null,
    cancelRequestedAt: null,
    cancelRequestedBy: null,
    stepExecutions: [createStepExecution({ id: 1, stepId: 5, status: 'RUNNING', completedAt: null, outputRows: null, log: null })],
  }));
  await mockApi(page, 'GET', `/api/v1/pipelines/${PIPELINE_ID}/executions/${FAILED_EXEC_ID}`, createExecutionDetail({
    id: FAILED_EXEC_ID,
    pipelineId: PIPELINE_ID,
    status: 'FAILED',
    completedAt: '2024-01-01T00:02:00Z',
    stepExecutions: [createStepExecution({ id: 2, stepId: 5, status: 'FAILED' })],
  }));
  await mockApi(page, 'GET', `/api/v1/pipelines/${PIPELINE_ID}/triggers`, [
    createTrigger({ id: 3, pipelineId: PIPELINE_ID, name: '매일 실행' }),
  ]);
  await mockApi(page, 'GET', `/api/v1/pipelines/${PIPELINE_ID}/trigger-events`, []);
  await mockApi(page, 'GET', '/api/v1/datasets', {
    content: [createDataset({ id: OUTPUT_DATASET_ID, name: '출력 데이터셋', tableName: 'out_ds' })],
    page: 0,
    size: 1000,
    totalElements: 1,
    totalPages: 1,
  });
  await mockApi(
    page,
    'GET',
    `/api/v1/datasets/${OUTPUT_DATASET_ID}`,
    createDatasetDetail({
      id: OUTPUT_DATASET_ID,
      name: '출력 데이터셋',
      columns: [createColumn({ id: 1, columnName: 'code', isPrimaryKey: true, columnOrder: 0 })],
    }),
  );
  await grantPipelinePermissions(page, permissions);
}

/** 상세(개요)를 열고 파이프라인 이름이 그려질 때까지 기다린다. */
async function openOverview(page: Page, permissions: string[]) {
  await setupDetailMocks(page, permissions);
  await gotoWithPermissions(page, `/pipelines/${PIPELINE_ID}`);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(createPipelineDetail().name);
  await nextFrame(page);
}

/** 트리거 탭을 열고 트리거 이름이 그려질 때까지 기다린다. */
async function openTriggers(page: Page, permissions: string[]) {
  await openOverview(page, permissions);
  await page.getByRole('tab', { name: '트리거' }).click();
  await expect(page.getByText('매일 실행', { exact: true })).toBeVisible();
  await nextFrame(page);
}

/** 트리거 카드의 ⋯ 메뉴 버튼 */
function triggerMenuButton(page: Page) {
  return page.getByRole('button', { name: '트리거 "매일 실행" 작업' });
}

test.describe('파이프라인 상세 — 권한별 쓰기 진입점 노출 (WD-219)', () => {
  test('조회 전용이면 헤더 「실행」「수정」이 없고 이름·탭은 그대로 보인다', async ({ authenticatedPage: page }) => {
    await openOverview(page, READ_ONLY);

    await expect(page.getByRole('button', { name: '실행', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '수정', exact: true })).toHaveCount(0);
    // 조회 동작은 그대로 — 탭 전환이 가능하다
    await expect(page.getByRole('tab', { name: '실행 이력' })).toBeVisible();
  });

  test('모든 권한이 있으면 같은 준비 후 헤더 「실행」「수정」이 보인다(대조군)', async ({ authenticatedPage: page }) => {
    await openOverview(page, PIPELINE_ALL_PERMISSIONS);

    await expect(page.getByRole('button', { name: '실행', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '수정', exact: true })).toBeVisible();
  });

  test('pipeline:execute 만 있으면 「실행」만, pipeline:write 만 있으면 「수정」만 보인다', async ({
    authenticatedPage: page,
  }) => {
    await openOverview(page, [...READ_ONLY, 'pipeline:execute']);
    await expect(page.getByRole('button', { name: '실행', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '수정', exact: true })).toHaveCount(0);

    await openOverview(page, [...READ_ONLY, 'pipeline:write']);
    await expect(page.getByRole('button', { name: '수정', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '실행', exact: true })).toHaveCount(0);
  });

  test('조회 전용이면 트리거 탭에 「트리거 추가」와 ⋯ 메뉴가 없고 트리거 목록은 보인다', async ({
    authenticatedPage: page,
  }) => {
    await openTriggers(page, READ_ONLY);

    await expect(page.getByRole('button', { name: '트리거 추가' })).toHaveCount(0);
    await expect(triggerMenuButton(page)).toHaveCount(0);
    await expect(page.getByText('최근 트리거 이벤트')).toBeVisible();
  });

  test('트리거 쓰기·삭제 권한이 있으면 「트리거 추가」와 ⋯ 메뉴(편집·비활성화·삭제)가 보인다(대조군)', async ({
    authenticatedPage: page,
  }) => {
    await openTriggers(page, PIPELINE_ALL_PERMISSIONS);

    await expect(page.getByRole('button', { name: '트리거 추가' })).toBeVisible();
    await triggerMenuButton(page).click();
    await expect(page.getByRole('menuitem', { name: '편집' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: '비활성화' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: '삭제' })).toBeVisible();
  });

  test('trigger:write 만 있으면 메뉴에 편집·비활성화만 있고 삭제는 없다', async ({ authenticatedPage: page }) => {
    await openTriggers(page, [...READ_ONLY, 'trigger:write']);

    await expect(page.getByRole('button', { name: '트리거 추가' })).toBeVisible();
    await triggerMenuButton(page).click();
    await expect(page.getByRole('menuitem', { name: '편집' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: '비활성화' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: '삭제' })).toHaveCount(0);
  });

  test('trigger:delete 만 있으면 「트리거 추가」는 없고 메뉴에 삭제만 있다', async ({ authenticatedPage: page }) => {
    await openTriggers(page, [...READ_ONLY, 'trigger:delete']);

    await expect(page.getByRole('button', { name: '트리거 추가' })).toHaveCount(0);
    await triggerMenuButton(page).click();
    await expect(page.getByRole('menuitem', { name: '삭제' })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: '편집' })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: /활성화/ })).toHaveCount(0);
  });

  test('실행 이력 행의 「실행 취소」는 pipeline:execute 가 있을 때만 보인다', async ({ authenticatedPage: page }) => {
    const rowCancel = page.getByRole('button', { name: `실행 #${RUNNING_EXEC_ID} 실행 취소` });

    await openOverview(page, READ_ONLY);
    await page.getByRole('tab', { name: '실행 이력' }).click();
    await expect(page.getByRole('cell', { name: String(RUNNING_EXEC_ID), exact: true })).toBeVisible();
    await nextFrame(page);
    await expect(rowCancel).toHaveCount(0);

    // 대조군 — 같은 절차에서 실행 권한이 있으면 진행 중 행에 버튼이 보인다
    await openOverview(page, [...READ_ONLY, 'pipeline:execute']);
    await page.getByRole('tab', { name: '실행 이력' }).click();
    await expect(page.getByRole('cell', { name: String(RUNNING_EXEC_ID), exact: true })).toBeVisible();
    await expect(rowCancel).toBeVisible();
  });

  test('실행 상세의 「재실행」「실행 취소」는 pipeline:execute 가 있을 때만 보인다', async ({ authenticatedPage: page }) => {
    // 끝난 실행 — 재실행
    await setupDetailMocks(page, READ_ONLY);
    await gotoWithPermissions(page, `/pipelines/${PIPELINE_ID}/executions/${FAILED_EXEC_ID}`);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(createPipelineDetail().name);
    await expect(page.locator('.react-flow__node').first()).toBeVisible();
    await nextFrame(page);
    await expect(page.getByRole('button', { name: '같은 입력으로 다시 실행' })).toHaveCount(0);

    // 진행 중 실행 — 실행 취소
    await gotoWithPermissions(page, `/pipelines/${PIPELINE_ID}/executions/${RUNNING_EXEC_ID}`);
    await expect(page.locator('.react-flow__node').first()).toBeVisible();
    await nextFrame(page);
    await expect(page.getByRole('button', { name: '실행 취소', exact: true })).toHaveCount(0);

    // 대조군
    await grantPipelinePermissions(page, [...READ_ONLY, 'pipeline:execute']);
    await gotoWithPermissions(page, `/pipelines/${PIPELINE_ID}/executions/${FAILED_EXEC_ID}`);
    await expect(page.getByRole('button', { name: '같은 입력으로 다시 실행' })).toBeVisible();
    await gotoWithPermissions(page, `/pipelines/${PIPELINE_ID}/executions/${RUNNING_EXEC_ID}`);
    await expect(page.getByRole('button', { name: '실행 취소', exact: true })).toBeVisible();
  });

  test('증분 처리 「처음부터 다시 만들기」는 pipeline:write 가 있을 때만 보인다(조회 모드)', async ({
    authenticatedPage: page,
  }) => {
    await openOverview(page, READ_ONLY);
    await page.locator('.react-flow__node').first().click();
    await expect(page.getByText(/마지막 처리 시점:/)).toBeVisible();
    await nextFrame(page);
    await expect(page.getByRole('button', { name: '처음부터 다시 만들기' })).toHaveCount(0);

    // 대조군 — 수정 모드로 들어가지 않은 조회 모드에서도 쓰기 권한이 있으면 예약할 수 있다
    await openOverview(page, [...READ_ONLY, 'pipeline:write']);
    await page.locator('.react-flow__node').first().click();
    await expect(page.getByText(/마지막 처리 시점:/)).toBeVisible();
    await expect(page.getByRole('button', { name: '처음부터 다시 만들기' })).toBeVisible();
  });
});

/** 파이프라인 목록을 열고 행 값이 그려질 때까지 기다린다(부재 단언 전 준비 절차). */
async function openPipelineList(page: Page, permissions: string[]) {
  await setupPipelineMocks(page, 3);
  await grantPipelinePermissions(page, permissions);
  await gotoWithPermissions(page, '/pipelines');
  await expect(page.getByRole('heading', { name: '파이프라인 관리' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '파이프라인 1', exact: true })).toBeVisible();
  await nextFrame(page);
}

test.describe('파이프라인 목록·생성 경로 — 권한별 추가·삭제 노출 (WD-219)', () => {
  test('조회 전용이면 「파이프라인 추가」와 행 삭제 버튼이 없고 목록은 보인다', async ({ authenticatedPage: page }) => {
    await openPipelineList(page, READ_ONLY);

    await expect(page.getByRole('link', { name: '파이프라인 추가' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toHaveCount(0);
    await expect(page.getByRole('cell', { name: '파이프라인 3', exact: true })).toBeVisible();
  });

  test('pipeline:write·pipeline:delete 가 있으면 같은 준비 후 「파이프라인 추가」와 행마다 삭제가 보인다(대조군)', async ({
    authenticatedPage: page,
  }) => {
    await openPipelineList(page, [...READ_ONLY, 'pipeline:write', 'pipeline:delete']);

    await expect(page.getByRole('link', { name: '파이프라인 추가' })).toBeVisible();
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toHaveCount(3);
  });

  test('pipeline:write 만 있으면 추가만, pipeline:delete 만 있으면 삭제만 보인다', async ({ authenticatedPage: page }) => {
    await openPipelineList(page, [...READ_ONLY, 'pipeline:write']);
    await expect(page.getByRole('link', { name: '파이프라인 추가' })).toBeVisible();
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toHaveCount(0);

    await openPipelineList(page, [...READ_ONLY, 'pipeline:delete']);
    await expect(page.getByRole('button', { name: '삭제', exact: true })).toHaveCount(3);
    await expect(page.getByRole('link', { name: '파이프라인 추가' })).toHaveCount(0);
  });

  test('pipeline:write 없이 /pipelines/new 로 직접 들어오면 편집기 대신 레이아웃 안 권한 안내가 보인다', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineMocks(page, 3);
    await grantPipelinePermissions(page, [...READ_ONLY, 'pipeline:delete', 'pipeline:execute']);
    await gotoWithPermissions(page, '/pipelines/new');

    await expect(page.getByTestId('screen-forbidden')).toBeVisible();
    await expect(page.getByTestId('screen-forbidden')).toContainText('이 화면을 볼 권한이 없습니다');
    // 레이아웃(사이드바) 안에 머문다 — 홈으로 튕기지 않는다
    await expect(page).toHaveURL(/\/pipelines\/new$/);
    await expect(page.getByRole('link', { name: '파이프라인' }).first()).toBeVisible();
    await expect(page.getByRole('heading', { name: '새 파이프라인' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0);
  });

  test('pipeline:write 가 있으면 같은 준비 후 /pipelines/new 에서 새 파이프라인 편집기가 열린다(대조군)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineMocks(page, 3);
    await grantPipelinePermissions(page, [...READ_ONLY, 'pipeline:write']);
    await gotoWithPermissions(page, '/pipelines/new');

    await expect(page.getByRole('heading', { name: '새 파이프라인' })).toBeAttached();
    await expect(page.getByRole('button', { name: '저장', exact: true })).toBeVisible();
    await expect(page.getByTestId('screen-forbidden')).toHaveCount(0);
  });
});
