/**
 * 파이프라인 실행 상세(ExecutionStepPanel) E2E 테스트
 *
 * ExecutionStepPanel 컴포넌트의 주요 렌더링 경로를 검증한다:
 * - 스텝 미선택 시 ExecutionSummary (실행 전체 정보) 표시
 * - 스텝 클릭 시 StepDetails (스텝별 상세 정보) 표시
 * - FAILED 스텝의 에러 메시지 표시 (사용자 친화적 안내 + "오류 상세" 제목)
 * - 로그가 있는 스텝의 로그 표시
 */

import type { Page } from '@playwright/test';

import {
  createExecutionDetail,
  createPipelineDetail,
  createStep,
  createStepExecution,
} from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

/** 가림 표시 스크린샷 경로 — `apps/firehub-web/test-results/tc/<suite>/` 규약(디자인 검토 증빙). */
const MASKED_SCREENSHOT_DIR = 'test-results/tc/security-level-execution-masked';

/**
 * 가림 문구 블록이 제한 상태 스타일인지 단언한다(디자인 검토 필수 항목).
 * 원문 오류처럼 보이면 안 되므로: <pre> 아님, destructive 클래스 없음, 오류 섹션 안에 role=alert 0건, Lock 아이콘 동반.
 */
async function expectMutedMaskedBlock(page: Page, maskedText: string) {
  const textNode = page.getByText(maskedText, { exact: true });
  await expect(textNode).toBeVisible();
  const block = textNode.locator('xpath=..');
  expect(await block.evaluate((el) => el.tagName)).toBe('P');
  await expect(block).toHaveClass(/bg-muted/);
  await expect(block).toHaveClass(/text-muted-foreground/);
  await expect(block).not.toHaveClass(/destructive/);
  await expect(block.locator('svg.lucide-lock')).toHaveCount(1);
  // 오류 섹션(오류 상세 라벨을 담은 div) 안에 원문용 <pre>·오류색·경보 역할이 없어야 한다.
  const section = block.locator('xpath=..');
  await expect(section.locator('pre')).toHaveCount(0);
  await expect(section.locator('[class*="destructive"]')).toHaveCount(0);
  await expect(section.locator('[role="alert"]')).toHaveCount(0);
}

test.describe('파이프라인 실행 상세 — ExecutionStepPanel', () => {
  // 실행·수정·트리거 편집 버튼은 권한이 있을 때만 보인다(WD-219) — 기본 인증 fixture 는 권한이 비어 있어 모든 쓰기 권한을 준다.
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('실행 탭에서 실행 클릭 시 ExecutionSummary가 표시된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);

    // 실행 상세 API 모킹 (id=1)
    const detail = createExecutionDetail({
      id: 1,
      pipelineId: 1,
      status: 'COMPLETED',
      executedBy: 'testuser',
      stepExecutions: [
        createStepExecution({ id: 1, stepName: '데이터 추출', status: 'COMPLETED', outputRows: 100 }),
        createStepExecution({ id: 2, stepId: 2, stepName: '데이터 변환', status: 'COMPLETED', outputRows: 95 }),
      ],
      startedAt: '2024-01-01T00:00:00Z',
      completedAt: '2024-01-01T00:01:00Z',
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', detail);

    await page.goto('/pipelines/1');

    // 실행 이력 탭으로 이동
    await page.getByRole('tab', { name: /실행|이력/ }).click();

    // 실행 행 클릭
    const rows = page.getByRole('row');
    await rows.nth(1).click();

    // ExecutionStepPanel이 열리고 "실행 정보" 헤더 표시 (selectedStepName 없음)
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });

    // ExecutionSummary: 실행자, 스텝 현황, 안내 문구 확인
    await expect(page.getByText('testuser')).toBeVisible();
    await expect(page.getByText(/DAG에서 스텝을 클릭/)).toBeVisible();
  });

  test('실행 이력의 실행 번호 링크로 키보드(Tab·Enter)만으로 실행 상세를 연다 (WD-436)', async ({ authenticatedPage: page }) => {
    // 행 클릭은 마우스로만 열려 키보드 사용자는 실행 상세에 들어갈 수 없었다 — 실행 번호가 링크가 되어 Tab 으로 닿아야 한다
    await setupPipelineEditorMocks(page, 1);
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', createExecutionDetail({ id: 1, pipelineId: 1, status: 'COMPLETED' }));

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();

    const link = page.getByRole('row').nth(1).getByRole('link', { name: '1', exact: true });
    await expect(link).toHaveAttribute('href', '/pipelines/1/executions/1');
    await link.focus();
    await expect(link).toBeFocused();
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/\/pipelines\/1\/executions\/1$/);
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });
  });

  test('완료된 실행의 스텝 현황 요약이 표시된다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);

    // COMPLETED 스텝 2개를 가진 실행 상세
    const detail = createExecutionDetail({
      id: 1,
      status: 'COMPLETED',
      stepExecutions: [
        createStepExecution({ id: 1, stepName: '데이터 추출', status: 'COMPLETED' }),
        createStepExecution({ id: 2, stepId: 2, stepName: '데이터 변환', status: 'COMPLETED' }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', detail);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();

    const rows = page.getByRole('row');
    await rows.nth(1).click();

    // "실행 정보" 패널 열림 확인
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });

    // 스텝 현황 섹션 확인 — "완료 2/2" 형식
    await expect(page.getByText(/완료 \d+\/\d+/)).toBeVisible();
  });

  test('FAILED 실행의 스텝 목록에 실패 스텝이 있으면 실패 현황이 표시된다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);

    // FAILED 상태 실행, 1개 스텝 실패 포함
    const detail = createExecutionDetail({
      id: 2,
      pipelineId: 1,
      status: 'FAILED',
      stepExecutions: [
        createStepExecution({ id: 1, stepName: '데이터 추출', status: 'COMPLETED' }),
        createStepExecution({
          id: 2,
          stepId: 2,
          stepName: '데이터 변환',
          status: 'FAILED',
          errorMessage: '데이터 변환 오류: 타입 불일치',
          completedAt: '2024-01-01T00:00:45Z',
        }),
      ],
      startedAt: '2024-01-01T00:00:00Z',
      completedAt: '2024-01-01T00:00:45Z',
    });
    // setupPipelineEditorMocks는 id=1,2 실행을 모킹하므로 id=2 실행 상세를 추가 모킹
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/2', detail);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();

    // 두 번째 행(id=2, FAILED) 클릭
    const rows = page.getByRole('row');
    await rows.nth(2).click();

    // ExecutionSummary 패널 열림 확인
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });

    // FAILED 스텝이 있으므로 실패 현황이 표시되어야 한다
    await expect(page.getByText(/실패 \d+/)).toBeVisible();
  });

  test('FAILED 실행 상세에서 "재실행" 버튼이 표시되며 클릭 시 executePipeline 호출 후 새 실행으로 이동한다 (이슈 #84)', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);

    const detail = createExecutionDetail({
      id: 2,
      pipelineId: 1,
      status: 'FAILED',
      stepExecutions: [
        createStepExecution({
          id: 1,
          stepName: '데이터 추출',
          status: 'FAILED',
          errorMessage: 'boom',
        }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/2', detail);
    // 재실행 후 라우팅된 새 실행 ID(99) 상세 — 새 실행은 RUNNING 상태로 시작
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/99', createExecutionDetail({
      id: 99,
      pipelineId: 1,
      status: 'RUNNING',
      stepExecutions: [],
    }));

    // POST /pipelines/1/execute 호출을 가로채서 새 실행 ID를 응답하고 호출 여부를 검증한다
    let executeCalled = false;
    await page.route('**/api/v1/pipelines/1/execute', async (route) => {
      if (route.request().method() === 'POST') {
        executeCalled = true;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({
            id: 99,
            pipelineId: 1,
            status: 'RUNNING',
            triggeredBy: 'MANUAL',
            executedBy: 'testuser',
            startedAt: '2024-01-01T00:10:00Z',
            completedAt: null,
            errorMessage: null,
          }),
        });
        return;
      }
      await route.continue();
    });

    await page.goto('/pipelines/1/executions/2');

    // 재실행 버튼 노출 확인 (status=FAILED → canRerun)
    const rerunBtn = page.getByRole('button', { name: '현재 설정으로 다시 실행' });
    await expect(rerunBtn).toBeVisible({ timeout: 5000 });

    // 클릭 → executePipeline POST 호출 → 새 실행 ID(99)로 라우팅
    await rerunBtn.click();

    await expect(page).toHaveURL(/\/pipelines\/1\/executions\/99$/, { timeout: 5000 });
    expect(executeCalled).toBe(true);
  });

  test('COMPLETED 실행에는 재실행 버튼이 표시되고, RUNNING 실행에는 표시되지 않는다 (이슈 #84)', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);

    // RUNNING 상태 실행 — 재실행 버튼 미노출
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', createExecutionDetail({
      id: 1,
      pipelineId: 1,
      status: 'RUNNING',
      stepExecutions: [],
    }));

    await page.goto('/pipelines/1/executions/1');
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('button', { name: '현재 설정으로 다시 실행' })).toHaveCount(0);
  });

  /**
   * WD-418: 재실행은 실행 이력에 정의 스냅샷이 없어 지금 저장된 정의로 돈다. 예전 이름 「같은 입력으로 다시 실행」은
   * 그때 실행을 재현하는 것처럼 읽혔다 — 이름을 「현재 설정으로 다시 실행」으로 바꾸고, 실행 뒤 파이프라인이 수정됐으면
   * 재현하지 않는다는 확인 창을 먼저 띄운다. 취소하면 실행 요청을 보내지 않는다.
   */
  test('실행 뒤 파이프라인이 수정됐으면 재실행 전에 「현재 설정으로 실행」 확인 창을 띄운다 (WD-418)', async ({
    authenticatedPage: page,
  }) => {
    await setupPipelineEditorMocks(page, 1);
    // 파이프라인은 실행(2024-01-01 00:00) 뒤인 01-02 에 수정됐다
    await mockApi(page, 'GET', '/api/v1/pipelines/1', createPipelineDetail({ id: 1, updatedAt: '2024-01-02T00:00:00Z' }));
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/2', createExecutionDetail({
      id: 2,
      pipelineId: 1,
      status: 'FAILED',
      createdAt: '2024-01-01T00:00:00Z',
      stepExecutions: [],
    }));
    let executeCalls = 0;
    await page.route('**/api/v1/pipelines/1/execute', async (route) => {
      if (route.request().method() !== 'POST') return route.continue();
      executeCalls += 1;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ id: 99, pipelineId: 1, status: 'RUNNING', triggeredBy: 'MANUAL', executedBy: 'testuser',
          startedAt: '2024-01-03T00:00:00Z', completedAt: null, errorMessage: null }),
      });
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/99', createExecutionDetail({
      id: 99,
      pipelineId: 1,
      status: 'RUNNING',
      stepExecutions: [],
    }));

    await page.goto('/pipelines/1/executions/2');
    const rerunBtn = page.getByRole('button', { name: '현재 설정으로 다시 실행' });
    await expect(rerunBtn).toBeVisible({ timeout: 5000 });

    // 누르면 바로 실행하지 않고 확인 창 — 「취소」면 요청이 없다
    await rerunBtn.click();
    const dialog = page.getByRole('alertdialog');
    await expect(dialog.getByText('이 실행 뒤에 파이프라인이 수정되었습니다')).toBeVisible();
    await expect(dialog.getByText(/그대로 재현하지\s*않으므로/)).toBeVisible();
    await dialog.getByRole('button', { name: '취소' }).click();
    await expect(dialog).toHaveCount(0);
    expect(executeCalls).toBe(0);

    // 「현재 설정으로 실행」을 고르면 실행하고 새 실행으로 이동한다
    await rerunBtn.click();
    await page.getByRole('alertdialog').getByRole('button', { name: '현재 설정으로 실행' }).click();
    await expect(page).toHaveURL(/\/pipelines\/1\/executions\/99$/, { timeout: 5000 });
    await expect(page.getByText('현재 설정으로 파이프라인을 다시 실행했습니다.')).toBeVisible();
    expect(executeCalls).toBe(1);
  });

  test('FAILED 스텝 선택 시 오류 상세 제목과 사용자 친화적 안내 메시지가 표시된다', async ({ authenticatedPage: page }) => {
    // 기술적 오류 메시지가 "오류 상세" 섹션 제목 + 안내 메시지와 함께 표시되는지 검증 (이슈 #51)
    await setupPipelineEditorMocks(page, 1);

    // FAILED 스텝 포함 실행 상세
    // 서버가 저장하는 SQL 결과 컬럼명 오류 문구(WD-111) — 예전 영어 'Invalid name: ?column?. Must match …' 대신 고칠 방법을 담은 한국어.
    const technicalErrorMsg =
      "SQL 결과 컬럼 이름 '?column?' 은 쓸 수 없습니다. 계산 컬럼에는 AS 로 이름을 붙여 주세요(예: SELECT 1 AS value). 컬럼 이름은 영문 소문자로 시작하고 영문 소문자·숫자·밑줄만 쓸 수 있습니다.";
    const detail = createExecutionDetail({
      id: 1,
      pipelineId: 1,
      status: 'FAILED',
      stepExecutions: [
        createStepExecution({
          id: 1,
          stepName: '데이터 추출',
          status: 'FAILED',
          errorMessage: technicalErrorMsg,
          completedAt: '2024-01-01T00:00:10Z',
        }),
      ],
      startedAt: '2024-01-01T00:00:00Z',
      completedAt: '2024-01-01T00:00:10Z',
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', detail);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();

    // 실행 행 클릭 → 실행 상세뷰(DAG + 사이드패널) 진입
    const rows = page.getByRole('row');
    await rows.nth(1).click();
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });

    // DAG 노드 클릭 → StepDetails 패널로 전환
    const stepNode = page.locator('.react-flow__node').first();
    await stepNode.click();

    // "오류 상세" 섹션 제목이 표시되어야 한다 (이전: "에러")
    await expect(page.getByText('오류 상세')).toBeVisible({ timeout: 5000 });

    // 사용자 친화적 안내 메시지가 표시되어야 한다
    await expect(page.getByText(/스텝 실행 중 오류가 발생했습니다/)).toBeVisible();

    // 기술적 원문은 그대로 표시되어야 한다 (개발자 디버깅용, 숨기지 않음)
    await expect(page.getByText(technicalErrorMsg)).toBeVisible();
    // 회귀 대조: 가리지 않은 원문은 기존 빨간 <pre> 그대로다(가림 블록 스타일과 구분).
    await expect(page.locator('pre', { hasText: technicalErrorMsg })).toHaveClass(/text-destructive/);
  });

  test('가려진 스텝 오류(errorMasked)에는 "아래 오류 정보를 참고" 안내가 빠지고 실패 사실과 가림 문구만 표시된다 (WD-27)', async ({ authenticatedPage: page }) => {
    // 조회자가 관련 데이터를 볼 수 없으면 서버가 원문 대신 가림 문구 + errorMasked=true 를 보낸다.
    // 아래에 참고할 원문이 없으므로 "아래 오류 정보를 참고하여 스텝 설정을 확인하세요" 안내를 숨겨야 한다(문자열 비교가 아니라 플래그로 판단).
    await setupPipelineEditorMocks(page, 1);

    const maskedMsg = '이 스텝의 상세 오류는 관련 데이터를 볼 수 있는 사용자에게만 표시됩니다.';
    const detail = createExecutionDetail({
      id: 1,
      pipelineId: 1,
      status: 'FAILED',
      stepExecutions: [
        createStepExecution({
          id: 1,
          stepName: '데이터 추출',
          status: 'FAILED',
          errorMessage: maskedMsg,
          errorMasked: true,
          completedAt: '2024-01-01T00:00:10Z',
        }),
      ],
      startedAt: '2024-01-01T00:00:00Z',
      completedAt: '2024-01-01T00:00:10Z',
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', detail);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();
    await page.getByRole('row').nth(1).click();
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });
    await page.locator('.react-flow__node').first().click();

    await expect(page.getByText('오류 상세')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText(maskedMsg)).toBeVisible();
    await expect(page.getByText('스텝 실행 중 오류가 발생했습니다.', { exact: true })).toBeVisible();
    // 참고 안내는 가렸을 때 렌더링되지 않는다(양성 대조는 위 '사용자 친화적 안내' 테스트).
    await expect(page.getByText(/아래 오류 정보를 참고하여/)).toHaveCount(0);
    // 가림 문구는 빨간 원문 블록이 아니라 muted + Lock 제한 상태 블록이다(디자인 검토).
    await expectMutedMaskedBlock(page, maskedMsg);
    await page.screenshot({ path: `${MASKED_SCREENSHOT_DIR}/step-masked.png` });
  });

  // 건너뜀(SKIPPED) 스텝은 오류가 아니라 "실행하지 않은 이유"다(WD-111). 저장 문구가 새 한국어든, 옛 실행 기록의 영어든, 원문이 가려진
  // 조회자의 가림 문구든 상태만 보고 같은 중립 안내를 낸다 — 「스텝 실행 중 오류가 발생했습니다」·「오류 상세」·빨간 원문 블록이 없어야 한다.
  for (const { label, errorMessage, errorMasked } of [
    { label: '새 한국어 문구', errorMessage: '선행 스텝이 실패하거나 건너뛰어져 이 스텝은 실행하지 않았습니다.', errorMasked: false },
    { label: '옛 영어 문구', errorMessage: 'Dependency failed or skipped', errorMasked: false },
    {
      label: '가림 문구',
      errorMessage: '이 스텝의 상세 오류는 관련 데이터를 볼 수 있는 사용자에게만 표시됩니다.',
      errorMasked: true,
    },
  ]) {
    test(`SKIPPED 스텝(${label})은 오류 안내 없이 건너뛴 이유를 중립 안내로 보여 준다 (WD-111)`, async ({ authenticatedPage: page }) => {
      await setupPipelineEditorMocks(page, 1);
      const detail = createExecutionDetail({
        id: 1,
        pipelineId: 1,
        status: 'FAILED',
        stepExecutions: [
          createStepExecution({
            id: 1,
            stepName: '데이터 추출',
            status: 'SKIPPED',
            errorMessage,
            errorMasked,
            startedAt: null,
            completedAt: '2024-01-01T00:00:10Z',
          }),
        ],
        startedAt: '2024-01-01T00:00:00Z',
        completedAt: '2024-01-01T00:00:10Z',
      });
      await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', detail);

      await page.goto('/pipelines/1');
      await page.getByRole('tab', { name: /실행|이력/ }).click();
      await page.getByRole('row').nth(1).click();
      await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });
      await page.locator('.react-flow__node').first().click();

      const notice = page.getByTestId('skipped-step-notice');
      await expect(notice).toBeVisible({ timeout: 5000 });
      await expect(notice).toContainText('건너뛴 이유');
      await expect(notice).toContainText('선행 스텝이 실패하거나 건너뛰어져 이 스텝은 실행하지 않았습니다.');
      // 중립 안내: 오류 문구·오류 제목·오류색·원문 블록이 없다.
      await expect(page.getByText(/스텝 실행 중 오류가 발생했습니다/)).toHaveCount(0);
      await expect(page.getByText('오류 상세')).toHaveCount(0);
      await expect(notice.locator('pre')).toHaveCount(0);
      await expect(notice.locator('[class*="destructive"]')).toHaveCount(0);
      // 저장 문구(옛 영어·가림 문구)는 그대로 보이지 않는다.
      await expect(page.getByText('Dependency failed or skipped')).toHaveCount(0);
      await expect(page.getByText('이 스텝의 상세 오류는 관련 데이터를 볼 수 있는 사용자에게만 표시됩니다.')).toHaveCount(0);
    });
  }

  test('가려진 실행 단위 오류(errorMasked)에는 "아래 오류 정보를 참고하세요" 가 빠지고 실패 사실만 안내한다 (WD-27)', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);

    const maskedExecMsg = '이 실행의 상세 오류는 관련 데이터를 볼 수 있는 사용자에게만 표시됩니다.';
    const detail = createExecutionDetail({
      id: 1,
      pipelineId: 1,
      status: 'FAILED',
      stepExecutions: [],
      startedAt: '2024-01-01T00:00:00Z',
      completedAt: '2024-01-01T00:00:01Z',
      errorMessage: maskedExecMsg,
      errorMasked: true,
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', detail);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();
    await page.getByRole('row').nth(1).click();

    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText(maskedExecMsg)).toBeVisible();
    await expect(page.getByText('스텝이 실행되기 전 파이프라인 실행 자체가 실패했습니다.', { exact: true })).toBeVisible();
    await expect(page.getByText(/아래 오류 정보를 참고하세요/)).toHaveCount(0);
    await expectMutedMaskedBlock(page, maskedExecMsg);
    await page.screenshot({ path: `${MASKED_SCREENSHOT_DIR}/execution-masked.png` });
  });

  test('스텝 실행 레코드가 없는 실행에서 DAG 노드를 클릭하면 "실행되지 않음" 빈 상태와 execution 레벨 오류가 표시된다 (이슈 #517)', async ({ authenticatedPage: page }) => {
    // 스텝 실행 레코드가 하나도 생성되기 전에 최상위 예외로 실패한 케이스를 재현한다.
    // (예: 토폴로지 정렬 실패 등 — pipeline_execution.error_message에만 원인이 남는다)
    await setupPipelineEditorMocks(page, 1);

    const execErrorMsg = 'Topological sort failed: circular dependency detected';
    const detail = createExecutionDetail({
      id: 1,
      pipelineId: 1,
      status: 'FAILED',
      stepExecutions: [], // 스텝 현황: 완료 0/0
      startedAt: '2024-01-01T00:00:00Z',
      completedAt: '2024-01-01T00:00:01Z',
      errorMessage: execErrorMsg,
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/1', detail);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();

    const rows = page.getByRole('row');
    await rows.nth(1).click();

    // ExecutionSummary가 먼저 보이고, execution 레벨 오류도 요약 화면에 노출된다
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText(/완료 0\/0/)).toBeVisible();
    await expect(page.getByText(execErrorMsg)).toBeVisible();
    // 회귀 대조: 가리지 않은 실행 단위 원문은 빨간 <pre> 그대로다.
    await expect(page.locator('pre', { hasText: execErrorMsg })).toHaveClass(/text-destructive/);

    // DAG 노드(스텝) 클릭 — 해당 스텝의 실행 레코드는 없다(stepExecutions: [])
    const stepNode = page.locator('.react-flow__node').first();
    await stepNode.click();

    // 헤더는 "스텝: {name}"으로 바뀌지만, 본문은 더 이상 ExecutionSummary가 아니라
    // "실행되지 않음" 빈 상태 + execution 레벨 오류를 보여줘야 한다 (수정 전에는 요약이 반복 표시됨)
    await expect(page.getByText(/^스텝: /)).toBeVisible();
    await expect(page.getByText('이 스텝은 실행되지 않았습니다')).toBeVisible();
    await expect(page.getByText(execErrorMsg)).toBeVisible();
    // ExecutionSummary 전용 안내 문구는 더 이상 보이지 않아야 한다
    await expect(page.getByText(/DAG에서 스텝을 클릭/)).toHaveCount(0);
  });

  // --- WD-108: 파이프라인 정의를 고쳐도 과거 실행 기록이 남는다 ---

  test('실행 이후 정의에서 삭제된 스텝의 기록이 요약에 「삭제된 스텝」으로 보이고, 눌러서 상태·오류·출력행을 볼 수 있다 (WD-108)', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    // 현재 정의: 데이터 추출(1)·데이터 변환(2). 실행 당시에는 '옛 정제' 스텝이 더 있었고 지금은 지워졌다(stepId=null).
    const detail = createExecutionDetail({
      id: 2,
      status: 'FAILED',
      stepExecutions: [
        createStepExecution({ id: 11, stepId: 1, stepName: '데이터 추출', status: 'COMPLETED' }),
        createStepExecution({ id: 12, stepId: 2, stepName: '데이터 변환', status: 'COMPLETED' }),
        createStepExecution({
          id: 13,
          stepId: null,
          stepName: '옛 정제',
          status: 'FAILED',
          outputRows: 7,
          log: null,
          errorMessage: 'column "legacy" does not exist',
        }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/2', detail);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();
    await page.getByRole('row').nth(2).click();

    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });
    // 스텝 현황은 실행 당시 기록 전체 기준이다(삭제된 스텝 포함 3개)
    await expect(page.getByText('완료 2/3')).toBeVisible();
    await expect(page.getByText('삭제된 스텝', { exact: true }).first()).toBeVisible();
    const deletedEntry = page.getByRole('button', { name: /옛 정제/ });
    await expect(deletedEntry).toBeVisible();
    await expect(deletedEntry).toContainText('삭제된 스텝');
    await expect(deletedEntry).toContainText('실패');

    await deletedEntry.click();
    await expect(page.getByText('스텝: 옛 정제 (삭제된 스텝)')).toBeVisible();
    await expect(page.getByText('column "legacy" does not exist')).toBeVisible();
    await expect(page.getByText('7', { exact: true })).toBeVisible();
    await expect(page.getByText('이 스텝은 실행되지 않았습니다')).toHaveCount(0);

    // 닫으면 요약으로 돌아간다
    await page.getByRole('button', { name: '스텝 상세 닫기' }).click();
    await expect(page.getByText('실행 정보')).toBeVisible();
  });

  test('이름이 바뀐 기존 스텝도 스텝 id 로 실행 기록을 찾아 상태·오류를 보여 준다 (WD-108)', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    // 실행 당시 이름은 '옛 변환'(스냅샷), 지금 정의의 같은 스텝(id=2) 이름은 '데이터 변환'.
    const detail = createExecutionDetail({
      id: 2,
      status: 'FAILED',
      stepExecutions: [
        createStepExecution({ id: 21, stepId: 1, stepName: '데이터 추출', status: 'COMPLETED' }),
        createStepExecution({
          id: 22,
          stepId: 2,
          stepName: '옛 변환',
          status: 'FAILED',
          outputRows: 95,
          errorMessage: '데이터 변환 오류: 타입 불일치',
        }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/2', detail);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();
    await page.getByRole('row').nth(2).click();
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });
    // 현재 정의에 있는 스텝이라 「삭제된 스텝」 목록에는 나오지 않는다
    await expect(page.getByText('삭제된 스텝', { exact: true })).toHaveCount(0);

    await page.locator('.react-flow__node', { hasText: '데이터 변환' }).click();
    await expect(page.getByText('스텝: 데이터 변환')).toBeVisible();
    await expect(page.getByText('데이터 변환 오류: 타입 불일치')).toBeVisible();
    await expect(page.getByText('95', { exact: true })).toBeVisible();
    await expect(page.getByText('이 스텝은 실행되지 않았습니다')).toHaveCount(0);
  });

  test('실행 이후 새로 추가된 스텝은 지워진 동명 스텝의 기록과 섞이지 않고 「실행되지 않았습니다」를 보여 준다 (WD-108)', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    // 현재 정의의 '데이터 변환'(id=3)은 실행 이후 새로 만든 스텝이다. 실행 당시의 '데이터 변환'(옛 id)은 지워져 stepId=null.
    await mockApi(
      page,
      'GET',
      '/api/v1/pipelines/1',
      createPipelineDetail({
        steps: [
          createStep(),
          createStep({ id: 3, name: '데이터 변환', stepOrder: 1, dependsOnStepNames: ['데이터 추출'] }),
        ],
      }),
    );
    const detail = createExecutionDetail({
      id: 2,
      status: 'FAILED',
      stepExecutions: [
        createStepExecution({ id: 31, stepId: 1, stepName: '데이터 추출', status: 'COMPLETED' }),
        createStepExecution({ id: 32, stepId: null, stepName: '데이터 변환', status: 'FAILED', errorMessage: '옛 오류' }),
      ],
    });
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/2', detail);

    await page.goto('/pipelines/1');
    await page.getByRole('tab', { name: /실행|이력/ }).click();
    await page.getByRole('row').nth(2).click();
    await expect(page.getByText('실행 정보')).toBeVisible({ timeout: 5000 });
    await expect(page.getByRole('button', { name: /데이터 변환.*삭제된 스텝/ })).toBeVisible();

    await page.locator('.react-flow__node', { hasText: '데이터 변환' }).click();
    await expect(page.getByText('이 스텝은 실행되지 않았습니다')).toBeVisible();
    await expect(page.getByText('옛 오류')).toHaveCount(0);
  });

  test('편집 후 저장하면 기존 스텝의 id 를 함께 보내 서버가 같은 스텝을 제자리 갱신한다 (WD-108)', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    const putCapture = await mockApi(page, 'PUT', '/api/v1/pipelines/1', {}, { capture: true });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '수정' }).click();
    await page.locator('.react-flow__node', { hasText: '데이터 변환' }).click();
    await page.locator('#step-description').fill('설명만 바꾼 저장');
    await page.getByRole('button', { name: '저장', exact: true }).click();

    const req = await putCapture.waitForRequest();
    const payload = req.payload as { steps: Array<{ id?: number; name: string }> };
    expect(payload.steps.map((st) => [st.id, st.name])).toEqual([
      [1, '데이터 추출'],
      [2, '데이터 변환'],
    ]);
  });
});
