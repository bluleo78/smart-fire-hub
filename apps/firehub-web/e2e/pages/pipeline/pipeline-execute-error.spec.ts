/**
 * 파이프라인 실행 실패 안내 E2E 테스트 (WD-110)
 *
 * 「실행」·「재실행」이 실패하면 서버가 보낸 사유(비활성 파이프라인·보안 등급 403 등)를 토스트로 보여 줘야 한다.
 * 예전엔 catch 가 사유를 버리고 「파이프라인 실행에 실패했습니다.」 고정 문구만 띄워 왜 실패했는지 알 수 없었다.
 */

import { createExecutionDetail, createStepExecution } from '../../factories/pipeline.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { grantPipelinePermissions, setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

const INACTIVE_MESSAGE = '비활성 파이프라인은 실행할 수 없습니다. 파이프라인 편집에서 활성화한 뒤 다시 실행하세요.';

test.describe('파이프라인 실행 실패 안내 (WD-110)', () => {
  // 실행·수정·트리거 편집 버튼은 권한이 있을 때만 보인다(WD-219) — 기본 인증 fixture 는 권한이 비어 있어 모든 쓰기 권한을 준다.
  test.beforeEach(async ({ authenticatedPage: page }) => {
    await grantPipelinePermissions(page);
  });

  test('비활성 파이프라인을 실행하면 서버 사유가 토스트에 보인다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    const executeCapture = await mockApi(
      page,
      'POST',
      '/api/v1/pipelines/1/execute',
      { status: 409, error: 'Conflict', message: INACTIVE_MESSAGE },
      { status: 409, capture: true },
    );

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '실행', exact: true }).click();

    await executeCapture.waitForRequest();
    await expect(page.locator('[data-sonner-toast]').filter({ hasText: INACTIVE_MESSAGE })).toBeVisible();
    await expect(page.getByText('파이프라인 실행에 실패했습니다.')).toHaveCount(0);
  });

  test('사유 없는 실패는 고정 문구로 안내한다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    await mockApi(page, 'POST', '/api/v1/pipelines/1/execute', {}, { status: 500 });

    await page.goto('/pipelines/1');
    await page.getByRole('button', { name: '실행', exact: true }).click();

    await expect(page.locator('[data-sonner-toast]').filter({ hasText: '파이프라인 실행에 실패했습니다.' })).toBeVisible();
  });

  test('재실행이 보안 등급으로 거부되면 서버 사유가 토스트에 보인다', async ({ authenticatedPage: page }) => {
    await setupPipelineEditorMocks(page, 1);
    const denied = '이 파이프라인이 읽는 데이터셋의 보안 등급이 내 등급보다 높아 실행할 수 없습니다.';
    await mockApi(
      page,
      'GET',
      '/api/v1/pipelines/1/executions/2',
      createExecutionDetail({
        id: 2,
        pipelineId: 1,
        status: 'FAILED',
        stepExecutions: [createStepExecution({ id: 1, stepName: '데이터 추출', status: 'FAILED' })],
        startedAt: '2024-01-01T00:00:00Z',
        completedAt: '2024-01-01T00:02:00Z',
      }),
    );
    await mockApi(page, 'POST', '/api/v1/pipelines/1/execute', { status: 403, error: 'Forbidden', message: denied }, { status: 403 });

    await page.goto('/pipelines/1/executions/2');
    await page.getByRole('button', { name: '같은 입력으로 다시 실행' }).click();

    await expect(page.locator('[data-sonner-toast]').filter({ hasText: denied })).toBeVisible();
    await expect(page.getByText('재실행에 실패했습니다.')).toHaveCount(0);
  });
});
