/**
 * dataset-manager 서브에이전트 E2E 테스트
 *
 * 시나리오: 사용자가 AI 챗에 CSV 파일을 첨부하고 "화재 데이터셋 만들어줘"로 요청 →
 *   1) dataset-manager가 GIS(lat/lng) 컬럼을 감지하여 GEOMETRY 변환 제안
 *   2) 사용자가 확인하면 신규 데이터셋 생성 완료 응답
 *
 * 프로젝트 E2E 컨벤션:
 * - 백엔드/ai-agent 없이 API 모킹 기반으로 동작한다(`apps/firehub-web/CLAUDE.md` 참조).
 * - AI 챗은 SSE 스트리밍이므로 `/api/v1/ai/chat`의 응답 본문을 SSE 이벤트 시퀀스로 모킹한다.
 * - 로그인은 `auth.fixture.ts`의 `authenticatedPage`를 사용한다.
 * - 스크린샷은 레포 루트 `snapshots/` 폴더(이미 .gitignore 처리됨)에 저장한다.
 */

import path from 'node:path';

import type { Page } from '@playwright/test';

import { expect, test } from '../../fixtures/auth.fixture';

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태로 변환 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** GIS 감지 단계(1차 요청) SSE 이벤트: 사용자가 확인을 줄 때까지 질문으로 끝낸다. */
const GIS_DETECT_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'dataset-manager-session-1' }),
  sseEvent({
    type: 'text',
    content:
      'CSV 파일을 분석했습니다.\n\n**공간 데이터(GIS) 감지**: `lat`, `lng` 컬럼이 발견되어 WGS84(EPSG:4326) GEOMETRY(Point) 컬럼으로 자동 변환을 제안합니다.\n\n아래 스키마로 신규 데이터셋을 생성할까요?\n- incident_no (VARCHAR)\n- occurred_at (TIMESTAMP)\n- location (GEOMETRY, 4326)\n- address (VARCHAR)\n- damage_amount (BIGINT)\n\n계속 진행하려면 "네, 만드세요"라고 답해 주세요.',
  }),
  sseEvent({ type: 'done', inputTokens: 300 }),
];

/** 데이터셋 생성 완료(2차 요청) SSE 이벤트. */
const CREATED_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'dataset-manager-session-1' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__create_dataset',
    input: {
      name: 'fire_incidents_sample',
      columns: ['incident_no', 'occurred_at', 'location', 'address', 'damage_amount'],
    },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__create_dataset',
    result: JSON.stringify({ datasetId: 9001, tableName: 'fire_incidents_sample' }),
    status: 'completed',
  }),
  sseEvent({
    type: 'text',
    content:
      '데이터셋 `fire_incidents_sample` 생성 완료되었습니다. 임포트 시작까지 마쳤으며, 3건의 레코드가 적재되었습니다.',
  }),
  sseEvent({ type: 'done', inputTokens: 450 }),
];

/**
 * /api/v1/ai/chat 엔드포인트를 순차적으로 모킹한다.
 * 1회차 POST → GIS 감지 응답, 2회차 POST → 생성 완료 응답을 돌려준다.
 * SSE 스트림 특성상 `route.fulfill()`로 전체 본문을 한 번에 반환해도 프론트엔드는 정상 파싱한다.
 */
async function mockChatSSESequence(page: Page) {
  let callCount = 0;
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      callCount += 1;
      const events = callCount === 1 ? GIS_DETECT_EVENTS : CREATED_EVENTS;
      await route.fulfill({
        status: 200,
        headers: {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache',
          Connection: 'keep-alive',
        },
        body: events.join(''),
      });
    },
  );
}

/** AI 세션 목록/생성 API 모킹 — ai-report-builder.spec.ts 패턴 재사용. */
async function mockAiSessions(page: Page) {
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
        body: JSON.stringify({
          id: 1,
          sessionId: 'dataset-manager-session-1',
          title: null,
          createdAt: '2026-04-11T00:00:00Z',
          updatedAt: '2026-04-11T00:00:00Z',
        }),
      });
    },
  );
}

/**
 * AI 챗 사이드 패널을 연다.
 * 레이아웃 헤더의 "AI 어시스턴트" 칩 클릭으로 AIProvider의 사이드 패널이 열린다.
 * (칩 label과 sr-only 텍스트 2개가 매치되므로 first()를 사용한다)
 */
async function openChatPanel(page: Page) {
  await page.getByText('AI 어시스턴트').first().click();
  await page
    .getByPlaceholder('메시지를 입력하세요...')
    .waitFor({ state: 'visible', timeout: 5000 });
}

test.describe('AI 챗 dataset-manager', () => {
  // TODO(Task 13): 실제 AI 백엔드와 드래그&드롭 파일 업로드 훅 통합 이후 flakiness를 재확인한다.
  // 본 테스트는 SSE 응답을 모킹하므로 AI API 키나 ai-agent 프로세스가 없어도 동작한다.
  test('CSV 첨부 → GIS 감지 → 신규 데이터셋 생성', async ({ authenticatedPage: page }) => {
    await mockChatSSESequence(page);
    await mockAiSessions(page);

    // 1. 패널 열기
    await openChatPanel(page);

    // 2. CSV 파일 첨부 — ChatInput의 hidden input[type="file"]에 직접 setInputFiles
    //    (fileInputRef 클릭은 UI 세부사항이므로 숨김 요소에 바로 파일을 주입하는 것이 안정적)
    const csvPath = path.resolve(
      __dirname,
      '..',
      '..',
      'fixtures',
      'fire-incidents-sample.csv',
    );
    await page.locator('input[type="file"]').setInputFiles(csvPath);
    await expect(page.getByText('fire-incidents-sample.csv')).toBeVisible();

    // 3. 메시지 전송 — "이 파일로 화재 데이터셋 만들어줘"
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('이 파일로 화재 데이터셋 만들어줘');
    await chatInput.press('Enter');

    // 4. GIS 감지 응답 대기 — GEOMETRY / 공간 데이터 / 4326 키워드 확인
    await expect(page.getByText(/공간 데이터\(GIS\) 감지/)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText(/GEOMETRY/)).toBeVisible();
    await expect(page.getByText(/4326/)).toBeVisible();

    // 5. 스크린샷 — GIS 감지 단계
    await page.screenshot({
      path: path.resolve(__dirname, '..', '..', '..', '..', '..', 'snapshots', 'dataset-manager-gis-detect.png'),
      fullPage: true,
    });

    // 6. 확인 응답 전송
    await chatInput.fill('네, 만드세요');
    await chatInput.press('Enter');

    // 7. 생성 완료 응답 대기
    await expect(page.getByText(/생성 완료/)).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText(/임포트 시작/)).toBeVisible();
    await expect(page.getByText(/fire_incidents_sample/)).toBeVisible();

    // 8. 스크린샷 — 생성 완료 단계
    await page.screenshot({
      path: path.resolve(__dirname, '..', '..', '..', '..', '..', 'snapshots', 'dataset-manager-created.png'),
      fullPage: true,
    });
  });
});
