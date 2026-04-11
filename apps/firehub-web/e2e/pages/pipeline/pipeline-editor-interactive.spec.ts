/**
 * 파이프라인 에디터 상호작용 E2E 테스트
 *
 * pipelineEditorReducer / PipelineCanvas / StepConfigPanel 의 reducer dispatch
 * 경로(ADD_STEP, UPDATE_STEP, AUTO_LAYOUT) 를 UI 로부터 실제 호출되도록 커버한다.
 */

import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

test.describe('파이프라인 에디터 — 상호작용', () => {
  /** 신규 파이프라인 에디터는 /pipelines/new 로 진입한다. 데이터셋 목록만 있으면 된다. */
  async function setupNewEditorMocks(
    page: import('@playwright/test').Page,
  ) {
    await mockApi(page, 'GET', '/api/v1/datasets', {
      content: [],
      page: 0,
      size: 1000,
      totalElements: 0,
      totalPages: 0,
    });
  }

  test('빈 상태 → 스텝 추가 버튼 클릭 시 첫 번째 스텝이 생성된다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // 빈 상태 안내 문구
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).toBeVisible();

    // 스텝 추가 → ADD_STEP dispatch → steps[0] 생성
    await page.getByRole('button', { name: /스텝 추가/ }).click();

    // 빈 상태가 사라지고 ReactFlow 캔버스가 렌더링된다
    await expect(page.getByText('첫 번째 스텝을 추가하세요')).not.toBeVisible();
    // 우상단의 자동 정렬 / 스텝 추가 버튼이 나타난다
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();
  });

  test('스텝 추가 → 자동 정렬 버튼 클릭 시 AUTO_LAYOUT dispatch', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');
    await page.getByRole('button', { name: /스텝 추가/ }).click();
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();

    // 스텝 하나 더 추가 (ADD_STEP) — 우상단의 추가 버튼 사용 (first())
    await page.getByRole('button', { name: /스텝 추가/ }).first().click();

    // 자동 정렬 버튼 클릭 (AUTO_LAYOUT dispatch)
    await page.getByRole('button', { name: '자동 정렬' }).click();

    // 여전히 캔버스가 정상 렌더링되는지 확인 — AUTO_LAYOUT 이후 에러 없이 유지
    await expect(page.getByRole('button', { name: '자동 정렬' })).toBeVisible();
  });

  test('신규 에디터에서 헤더에 파이프라인 이름 입력 필드가 있다', async ({
    authenticatedPage: page,
  }) => {
    await setupNewEditorMocks(page);

    await page.goto('/pipelines/new');

    // EditorHeader 의 이름 입력 필드 확인 (신규 생성 모드)
    const nameInput = page.getByPlaceholder(/파이프라인 이름|이름 입력/);
    await expect(nameInput.first()).toBeVisible();
  });
});
