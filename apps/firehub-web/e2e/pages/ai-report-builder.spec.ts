/**
 * AI 리포트 빌더 위젯 E2E 테스트
 *
 * generate_report 도구 결과가 ReportBuilderWidget으로 렌더링되는지,
 * 섹션 타입별 렌더링(cards 카드 그리드, list 목록 등)이 올바른지,
 * 미리보기 다이얼로그가 정상 동작하는지 검증한다.
 *
 * AI 챗은 SSE 스트리밍이므로 page.route()로 SSE 응답을 모킹하여 테스트한다.
 */

import { expect,test } from '../fixtures/auth.fixture';

/** SSE 이벤트 직렬화 — 각 이벤트를 "data: {json}\n\n" 형태로 변환 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** generate_report 도구 결과로 사용할 테스트 데이터 */
const REPORT_TOOL_INPUT = {
  title: '매출 분석 리포트',
  question: '이번 달 매출이 왜 떨어졌나요?',
  templateStructure: {
    sections: [
      { key: 'executive_summary', label: '핵심 요약', type: 'text', required: true },
      { key: 'key_metrics', label: '주요 지표', type: 'cards', required: true },
      { key: 'root_cause', label: '원인 분석', type: 'list', required: true },
      { key: 'action_items', label: '권장 조치', type: 'recommendation' },
      { key: 'period_comparison', label: '기간 비교', type: 'comparison' },
    ],
    output_format: 'markdown',
  },
  sectionContents: {
    executive_summary: '이번 달 매출은 전월 대비 **15% 감소**했습니다. 주요 원인은 계절적 수요 감소와 경쟁사 프로모션입니다.',
    key_metrics:
      '주요 지표 요약:\n\n```json\n[{"title":"총 매출","value":"₩4,250만","description":"전월 대비 -15%"},{"title":"주문 건수","value":"1,230건","description":"전월 대비 -8%"},{"title":"객단가","value":"₩34,500","description":"전월 대비 -7%"}]\n```',
    root_cause:
      '- **계절적 수요 감소**: 비수기 진입으로 전체 시장 수요 하락\n- **경쟁사 프로모션**: A사의 대규모 할인 행사로 고객 이탈\n- **신규 유입 감소**: 마케팅 예산 축소로 신규 고객 유입률 20% 하락',
    action_items:
      '1. **마케팅 예산 재배분** — SNS 광고 비중 확대 (기대 효과: 신규 유입 15% 증가, 우선순위: 높음)\n2. **멤버십 할인 이벤트** — 기존 고객 재구매 유도 (기대 효과: 객단가 10% 개선, 우선순위: 중간)',
    period_comparison: '이번 달 vs 지난 달: 매출 -15% (₩5,000만 → ₩4,250만)\n이번 달 vs 전년 동기: 매출 +3% (₩4,120만 → ₩4,250만)',
  },
};

/**
 * AI 챗 SSE 엔드포인트를 모킹하여 generate_report 도구 결과를 반환한다.
 * 실제 AI 응답과 동일한 SSE 이벤트 시퀀스를 생성:
 * init → text → tool_use → tool_result → done
 */
async function mockChatSSEWithReportWidget(page: import('@playwright/test').Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();

      // SSE 이벤트 시퀀스 생성
      const events = [
        sseEvent({ type: 'init', sessionId: 'test-session-1' }),
        sseEvent({ type: 'text', content: '매출 데이터를 분석했습니다. 리포트를 생성합니다.\n\n' }),
        sseEvent({
          type: 'tool_use',
          toolName: 'mcp__firehub__generate_report',
          input: REPORT_TOOL_INPUT,
          status: 'started',
        }),
        sseEvent({
          type: 'tool_result',
          toolName: 'mcp__firehub__generate_report',
          result: JSON.stringify({ widgetType: 'report_builder', ...REPORT_TOOL_INPUT }),
          status: 'completed',
        }),
        sseEvent({ type: 'done', inputTokens: 500 }),
      ];

      const body = events.join('');

      await route.fulfill({
        status: 200,
        headers: {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache',
          'Connection': 'keep-alive',
        },
        body,
      });
    },
  );
}

/**
 * AI 챗 패널을 열고 메시지를 전송하는 헬퍼.
 * AI 어시스턴트 칩 클릭으로 사이드 패널을 열고, 입력란에 메시지를 전송한다.
 */
async function openChatAndSend(page: import('@playwright/test').Page, message: string) {
  // AI 세션 목록/생성 API 모킹
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([]),
        });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ id: 1, sessionId: 'test-session-1', title: null, createdAt: '2026-04-07T00:00:00Z', updatedAt: '2026-04-07T00:00:00Z' }),
      });
    },
  );

  // AI 패널은 모든 인증 페이지에서 접근 가능 — 홈으로 이동 후 패널 열기
  await page.goto('/', { waitUntil: 'commit' });
  // AI 어시스턴트 칩 클릭으로 사이드 패널 열기 (칩 label과 sr-only 2개 매치되므로 first)
  await page.getByText('AI 어시스턴트').first().click();

  // 채팅 입력란이 보일 때까지 대기 후 메시지 전송
  const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
  await chatInput.waitFor({ state: 'visible', timeout: 5000 });
  await chatInput.fill(message);
  await chatInput.press('Enter');
}

test.describe('AI 리포트 빌더 위젯', () => {
  test('generate_report 도구 결과가 위젯으로 렌더링된다', async ({ authenticatedPage: page }) => {
    await mockChatSSEWithReportWidget(page);
    await openChatAndSend(page, '이번 달 매출이 왜 떨어졌나요?');

    // 위젯 제목 확인
    await expect(page.getByText('매출 분석 리포트')).toBeVisible({ timeout: 10000 });

    // 원본 질문 표시 확인
    await expect(page.getByText('"이번 달 매출이 왜 떨어졌나요?"')).toBeVisible();

    // 섹션 레이블 표시 확인 (exact: true로 콘텐츠 텍스트와 구분)
    await expect(page.getByText('핵심 요약', { exact: true })).toBeVisible();
    await expect(page.getByText('주요 지표', { exact: true })).toBeVisible();
    await expect(page.getByText('원인 분석', { exact: true })).toBeVisible();

    // 섹션 타입 뱃지 표시 확인
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: 'text' }).first()).toBeVisible();
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: 'cards' })).toBeVisible();
    await expect(page.locator('[data-slot="badge"]').filter({ hasText: 'list' })).toBeVisible();

    // 텍스트 섹션 마크다운 렌더링 확인 — 볼드 텍스트가 렌더링됨
    await expect(page.getByText('15% 감소')).toBeVisible();
  });

  test('cards 타입 섹션이 카드 그리드로 렌더링된다', async ({ authenticatedPage: page }) => {
    await mockChatSSEWithReportWidget(page);
    await openChatAndSend(page, '이번 달 매출이 왜 떨어졌나요?');

    // 위젯이 렌더링될 때까지 대기
    await expect(page.getByText('매출 분석 리포트')).toBeVisible({ timeout: 10000 });

    // 미리보기 다이얼로그 열기 — 사이드 패널은 250px로 스크롤 제한이 있으므로
    await page.getByRole('button', { name: '미리보기' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    // cards 섹션의 JSON이 카드로 파싱되어 표시됨 (exact로 다른 섹션 텍스트와 구분)
    await expect(dialog.getByText('총 매출', { exact: true })).toBeVisible();
    await expect(dialog.getByText('₩4,250만', { exact: true })).toBeVisible();
    await expect(dialog.getByText('전월 대비 -15%', { exact: true })).toBeVisible();

    // 주문 건수 카드
    await expect(dialog.getByText('주문 건수', { exact: true })).toBeVisible();
    await expect(dialog.getByText('1,230건', { exact: true })).toBeVisible();

    // 객단가 카드
    await expect(dialog.getByText('객단가', { exact: true })).toBeVisible();
    await expect(dialog.getByText('₩34,500', { exact: true })).toBeVisible();
  });

  test('미리보기 버튼을 클릭하면 전체 리포트 다이얼로그가 열린다', async ({ authenticatedPage: page }) => {
    await mockChatSSEWithReportWidget(page);
    await openChatAndSend(page, '이번 달 매출이 왜 떨어졌나요?');

    // 위젯이 렌더링될 때까지 대기
    await expect(page.getByText('매출 분석 리포트')).toBeVisible({ timeout: 10000 });

    // "미리보기" 버튼 클릭
    await page.getByRole('button', { name: '미리보기' }).click();

    // 다이얼로그가 열림 — 제목과 모든 섹션이 표시됨
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    // 다이얼로그 내에서 모든 섹션 확인
    await expect(dialog.getByText('핵심 요약')).toBeVisible();
    await expect(dialog.getByText('주요 지표', { exact: true })).toBeVisible();
    await expect(dialog.getByText('원인 분석')).toBeVisible();
    await expect(dialog.getByText('권장 조치')).toBeVisible();
    await expect(dialog.getByText('기간 비교')).toBeVisible();

    // recommendation 섹션 콘텐츠 확인
    await expect(dialog.getByText('마케팅 예산 재배분')).toBeVisible();

    // comparison 섹션 콘텐츠 확인 — bg-muted/50 배경으로 구분됨
    await expect(dialog.getByText(/₩5,000만 → ₩4,250만/)).toBeVisible();

    // "닫기" 버튼으로 다이얼로그 닫기 — data-slot="button"인 푸터 닫기 버튼 (X sr-only 버튼과 구분)
    await dialog.locator('[data-slot="button"]:has-text("닫기")').click();
    await expect(dialog).not.toBeVisible();
  });

  test('저장 버튼을 클릭하면 스마트 작업 생성 페이지로 이동한다', async ({ authenticatedPage: page }) => {
    await mockChatSSEWithReportWidget(page);

    // 네비게이션 대상 페이지 모킹
    await page.route(
      (url) => url.pathname.includes('/ai-insights/jobs/new'),
      (route) => route.fulfill({ status: 200, body: '<html></html>' }),
    );

    await openChatAndSend(page, '이번 달 매출이 왜 떨어졌나요?');

    // 위젯이 렌더링될 때까지 대기
    await expect(page.getByText('매출 분석 리포트')).toBeVisible({ timeout: 10000 });

    // "저장" 버튼 클릭 → 스마트 작업 생성 페이지로 이동
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page).toHaveURL(/ai-insights\/jobs\/new/);
  });

  test('섹션 콘텐츠가 없는 경우 빈 상태를 표시한다', async ({ authenticatedPage: page }) => {
    // sectionContents가 비어 있는 응답 모킹
    const emptyInput = {
      ...REPORT_TOOL_INPUT,
      sectionContents: { executive_summary: '요약 내용만 있습니다.' },
    };

    await page.route(
      (url) => url.pathname === '/api/v1/ai/chat',
      async (route) => {
        if (route.request().method() !== 'POST') return route.fallback();
        const events = [
          sseEvent({ type: 'init', sessionId: 'test-session-2' }),
          sseEvent({
            type: 'tool_use',
            toolName: 'mcp__firehub__generate_report',
            input: emptyInput,
            status: 'started',
          }),
          sseEvent({
            type: 'tool_result',
            toolName: 'mcp__firehub__generate_report',
            result: JSON.stringify({ widgetType: 'report_builder', ...emptyInput }),
            status: 'completed',
          }),
          sseEvent({ type: 'done', inputTokens: 100 }),
        ];
        await route.fulfill({
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
          body: events.join(''),
        });
      },
    );

    await openChatAndSend(page, '테스트');

    await expect(page.getByText('매출 분석 리포트')).toBeVisible({ timeout: 10000 });

    // 콘텐츠가 있는 섹션은 표시
    await expect(page.getByText('요약 내용만 있습니다.')).toBeVisible();

    // 콘텐츠가 없는 섹션은 빈 상태 표시
    const emptyLabels = page.getByText('(내용 없음)');
    await expect(emptyLabels.first()).toBeVisible();
  });
});
