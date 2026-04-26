/**
 * PipelineEditorPage — 존재하지 않는 실행 ID 접근 시 에러 처리 E2E 테스트
 *
 * 이슈 #47: 존재하지 않는 실행 ID(400 응답)로 직접 URL 접근 시
 * toast 에러 알림 + 파이프라인 페이지 리다이렉트가 올바르게 동작하는지 검증한다.
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';
import { setupPipelineEditorMocks } from '../../fixtures/pipeline.fixture';

test.describe('PipelineEditorPage — 존재하지 않는 실행 ID 접근 에러 처리', () => {
  test('존재하지 않는 실행 ID로 직접 접근 시 toast.error를 표시하고 파이프라인 페이지로 리다이렉트한다', async ({
    authenticatedPage: page,
  }) => {
    // 파이프라인 에디터 기본 모킹 설정 (파이프라인 1 정보)
    await setupPipelineEditorMocks(page, 1);

    // 존재하지 않는 실행 ID에 대해 400 에러 응답을 모킹한다.
    // 실제 서버는 잘못된 실행 ID 요청 시 400 Bad Request를 반환한다.
    await mockApi(page, 'GET', '/api/v1/pipelines/1/executions/9999', null, { status: 400 });

    // 존재하지 않는 실행 ID URL로 직접 접근 시도
    await page.goto('/pipelines/1/executions/9999');

    // toast.error 메시지 '실행 정보를 불러오는데 실패했습니다.'가 표시되어야 한다
    await expect(page.getByText('실행 정보를 불러오는데 실패했습니다.')).toBeVisible({
      timeout: 5000,
    });

    // 파이프라인 페이지 /pipelines/1 으로 리다이렉트되어야 한다
    await page.waitForURL(/\/pipelines\/1$/, { timeout: 5000 });
    expect(page.url()).toMatch(/\/pipelines\/1$/);
  });
});
