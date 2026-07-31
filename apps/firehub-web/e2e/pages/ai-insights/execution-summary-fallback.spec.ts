import { createJobExecution } from '../../factories/ai-insight.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

/**
 * 실행 상세 — HTML 없는 폴백 경로의 요약 누락 (#363) E2E 회귀 테스트
 *
 * `/html` 엔드포인트는 htmlContent가 비면 404를 반환하도록 설계돼 있고, 프론트는 그 경우
 * sections를 마크다운 카드로 렌더하는 폴백 분기를 갖는다. 즉 폴백은 예외 경로가 아니다.
 * 그런데 요약 블록이 rawHtml 분기 안에만 있어, 이 정상 경로에서 AI가 만든 총평이 통째로 사라졌다.
 *
 * 검증 포인트는 "요약 요소가 있는가"가 아니라 "result.summary 값이 실제로 화면에 나오는가"다 —
 * 섹션 label이 '요약'인 경우가 흔해서 요소 존재만 보면 공허하게 통과한다.
 */

const SUMMARY_TEXT = '이번 주 처리량이 지난주 대비 12% 증가했습니다.';
const SECTION_TEXT = '섹션 본문 내용';

/** htmlContent 없이 summary + sections만 가진 COMPLETED 실행 (#363의 재현 조건) */
function fallbackExecution() {
  return createJobExecution({
    id: 1,
    jobId: 1,
    status: 'COMPLETED',
    result: {
      summary: SUMMARY_TEXT,
      sections: [{ key: 'detail', type: 'text', label: '상세', content: SECTION_TEXT }],
    },
  });
}

test.describe('실행 상세 — HTML 없는 폴백의 요약 (#363)', () => {
  test('htmlContent가 없어도 result.summary가 화면에 표시된다', async ({
    authenticatedPage: page,
  }) => {
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/1', fallbackExecution());
    // htmlContent가 없을 때의 실제 서버 동작 — 404가 폴백 분기의 진입 조건이다
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/1/html', '', { status: 404 });
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs/1/executions/1');

    // 이 이슈의 현상 — 폴백 경로에서 총평이 통째로 사라졌다
    await expect(page.getByTestId('execution-summary')).toContainText(SUMMARY_TEXT);

    // 섹션 폴백도 함께 살아 있어야 한다 (요약이 섹션을 대체하는 게 아니다)
    await expect(page.getByText(SECTION_TEXT)).toBeVisible();
  });

  test('HTML 리포트가 있는 경로에서도 요약이 계속 표시된다', async ({
    authenticatedPage: page,
  }) => {
    // 분기 밖으로 끌어올리면서 기존 HTML 경로가 요약을 잃지 않았는지 확인한다
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/1', fallbackExecution());
    await mockApi(
      page,
      'GET',
      '/api/v1/proactive/jobs/1/executions/1/html',
      '<html><body><h1>리포트 본문</h1></body></html>',
    );
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs/1/executions/1');

    await expect(page.getByTestId('execution-summary')).toContainText(SUMMARY_TEXT);
  });

  test('summary가 없으면 빈 요약 블록을 만들지 않는다', async ({ authenticatedPage: page }) => {
    const noSummary = createJobExecution({
      id: 2,
      jobId: 1,
      status: 'COMPLETED',
      result: { sections: [{ key: 'detail', type: 'text', label: '상세', content: SECTION_TEXT }] },
    });
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/2', noSummary);
    await mockApi(page, 'GET', '/api/v1/proactive/jobs/1/executions/2/html', '', { status: 404 });
    await mockApi(page, 'GET', '/api/v1/proactive/messages/unread-count', { count: 0 });

    await page.goto('/ai-insights/jobs/1/executions/2');

    await expect(page.getByText(SECTION_TEXT)).toBeVisible();
    await expect(page.getByTestId('execution-summary')).toHaveCount(0);
  });
});
