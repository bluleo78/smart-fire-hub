import { createJobExecution } from '../../factories/ai-insight.factory';
import { setupExecutionDetailMocks } from '../../fixtures/ai-insight.fixture';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 실행 상세(ExecutionDetail) 페이지 E2E 테스트
 * - 실행 상태(COMPLETED/FAILED/RUNNING)에 따른 UI 렌더링을 검증한다.
 */
test.describe('실행 상세 페이지', () => {
  test('완료된 실행의 메타 정보가 올바르게 표시된다', { tag: '@smoke' }, async ({ authenticatedPage: page }) => {
    // COMPLETED 상태 실행 모킹
    // 팩토리 기본값: deliveredChannels:['email'], startedAt:'2024-01-01T09:00:00Z'
    await setupExecutionDetailMocks(page, 1, 1, 'COMPLETED');

    await page.goto('/ai-insights/jobs/1/executions/1');

    // 실행 번호 헤더 확인
    await expect(page.getByRole('heading', { name: '실행 #1' })).toBeVisible();

    // 메타 카드의 "상태" 레이블 확인
    await expect(page.getByText('상태')).toBeVisible();

    // "완료" 뱃지 확인 (COMPLETED → 완료)
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '완료' })).toBeVisible();

    // 실행 시각 카드 확인
    await expect(page.getByText('실행 시각')).toBeVisible();

    // 실행 시각 값이 렌더링되는지 확인 — startedAt '2024-01-01T09:00:00Z' 기반으로 날짜 텍스트가 표시되어야 한다
    // UI에서 날짜 포맷이 다양할 수 있으므로 연도 '2024' 또는 '01' 등 날짜 관련 텍스트 존재 여부로 검증
    const timeText = page.getByText(/2024|1월|Jan/);
    await expect(timeText.first()).toBeVisible();

    // deliveredChannels 'email' 채널 정보가 어딘가에 표시되는지 확인
    // UI에 따라 'email', '이메일', 또는 관련 아이콘 레이블로 표시될 수 있다
    const emailChannel = page.getByText(/email|이메일/i);
    const hasEmailInfo = (await emailChannel.count()) > 0;
    expect(hasEmailInfo).toBe(true);
  });

  test('실패한 실행에서 에러 정보가 표시된다', async ({ authenticatedPage: page }) => {
    // FAILED 상태 실행 모킹 (errorMessage 포함)
    const failedExecution = createJobExecution({
      id: 2,
      jobId: 1,
      status: 'FAILED',
      errorMessage: 'AI 서비스 연결 실패',
      completedAt: null,
    });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/2', failedExecution);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs/1/executions/2');

    // "실패" 뱃지 확인 (FAILED → 실패)
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '실패' })).toBeVisible();

    // 에러 메시지 텍스트 확인
    await expect(page.getByText('AI 서비스 연결 실패')).toBeVisible();
  });

  test('실행 중인 상태에서 스피너와 안내 문구가 표시된다', async ({ authenticatedPage: page }) => {
    // RUNNING 상태 실행 모킹
    const runningExecution = createJobExecution({
      id: 3,
      jobId: 1,
      status: 'RUNNING',
      completedAt: null,
    });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/3', runningExecution);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs/1/executions/3');

    // "실행중" 뱃지 확인 — getStatusLabel('RUNNING') = '실행중' (공백 없음)
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '실행중' })).toBeVisible();

    // 리포트 생성 중 안내 문구 확인
    await expect(page.getByText('리포트를 생성하고 있습니다...')).toBeVisible();
  });

  test('뒤로 버튼이 표시된다', async ({ authenticatedPage: page }) => {
    await setupExecutionDetailMocks(page, 1, 1, 'COMPLETED');

    await page.goto('/ai-insights/jobs/1/executions/1');

    // "뒤로" 버튼 확인
    await expect(page.getByRole('button', { name: '뒤로' })).toBeVisible();
  });

  test('에러 메시지에 긴 URL이 포함되어도 카드 너비를 초과하지 않는다 (break-words 회귀)', async ({ authenticatedPage: page }) => {
    // 긴 URL이 포함된 에러 메시지로 overflow 발생 여부를 검증한다
    // break-words 클래스가 없으면 p 요소의 scrollWidth > clientWidth 가 되어 수평 오버플로가 발생한다
    const longErrorMessage =
      'Connection error: https://very-long-url.example.com/api/v1/endpoint?param=value&another=longlonglonglonglonglonglonglongvalue&yet=another_very_long_parameter_that_exceeds_card_width';

    const failedExecution = createJobExecution({
      id: 4,
      jobId: 1,
      status: 'FAILED',
      errorMessage: longErrorMessage,
      completedAt: null,
    });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/4', failedExecution);
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs/1/executions/4');

    // 에러 메시지 요소가 렌더링되었는지 확인
    const errorParagraph = page.locator('.font-mono.whitespace-pre-wrap');
    await expect(errorParagraph).toBeVisible();

    // overflow 검증: scrollWidth <= clientWidth 이면 텍스트가 카드 내에서 줄바꿈됨
    const isOverflowing = await errorParagraph.evaluate(
      (el) => el.scrollWidth > el.clientWidth,
    );
    expect(isOverflowing).toBe(false);
  });

  /**
   * WD-191 — 서버 사유가 한국어로 바뀐 뒤 영어 키워드 분류기가 엉뚱한 제목·조언을 붙이던 회귀.
   * 서버 원문 사유는 그대로 보이고, 제목·조언·카드 톤만 사유에 맞게 바뀌는지 본다.
   */
  test('AI 키 미설정 실패는 AI 인증 정보 등록을 안내하고 재시도 안내를 하지 않는다 (WD-191)', async ({ authenticatedPage: page }) => {
    const reason = 'AI API 키 또는 OAuth 토큰이 설정되지 않았습니다. 관리자 설정에서 등록하세요.';
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/1/executions/5',
      createJobExecution({ id: 5, jobId: 1, status: 'FAILED', errorMessage: reason, deliveredChannels: [] }),
    );
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs/1/executions/5');

    const card = page.getByTestId('execution-error');
    await expect(card).toBeVisible();
    await expect(card.getByRole('heading', { name: 'AI 인증 정보 확인 필요' })).toBeVisible();
    // 서버 원문 사유는 그대로 보인다
    await expect(card.getByText(reason)).toBeVisible();
    await expect(card).toContainText('관리 > 설정 > AI 에이전트 탭에서 AI 인증 정보');
    // 예전 오분류(AI 모델 오류 + 잠시 후 수동 실행)가 아니어야 한다
    await expect(card).not.toContainText('AI 모델 오류');
    await expect(card).not.toContainText('잠시 후 수동 실행');
    // 실패는 실패로 표시한다
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '실패' })).toBeVisible();
  });

  test('작업 주인 정지로 건너뛴 실행은 오류가 아닌 건너뜀으로 보인다 (WD-191)', async ({ authenticatedPage: page }) => {
    // 서버는 건너뜀도 status=FAILED 로 남긴다 — 사유 문구로 건너뜀을 가려내야 한다
    const reason = '작업 주인이 정지됐거나 워크스페이스에서 제거돼 실행하지 않았습니다';
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/1/executions/6',
      createJobExecution({ id: 6, jobId: 1, status: 'FAILED', errorMessage: reason, deliveredChannels: [] }),
    );
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs/1/executions/6');

    const card = page.getByTestId('execution-skipped');
    await expect(card).toBeVisible();
    await expect(card.getByRole('heading', { name: '건너뜀' })).toBeVisible();
    await expect(card.getByText(reason)).toBeVisible();
    await expect(card).toContainText('오류가 아닙니다');
    // 오류(빨강) 카드·「기타 오류」·「실패」 뱃지는 나오지 않는다
    await expect(page.getByTestId('execution-error')).toHaveCount(0);
    await expect(page.getByText('기타 오류')).toHaveCount(0);
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '건너뜀' })).toBeVisible();
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: '실패' })).toHaveCount(0);
  });
});

