/**
 * show_dataset_files 챗 위젯 E2E 테스트
 *
 * 시나리오: AI 챗이 FILE(오브젝트) 데이터셋의 파일 목록을 인라인 카드(show_dataset_files)로
 *   렌더한다. 위젯은 Reference 패턴 — 에이전트는 datasetId만 넘기고, 프론트엔드가 직접
 *   /datasets/{id}/objects 를 조회해 목록을 그리며, 파일 클릭 시 presigned URL을 발급한다.
 *
 * 프로젝트 E2E 컨벤션:
 * - 백엔드/ai-agent 없이 API 모킹 기반으로 동작한다(`apps/firehub-web/CLAUDE.md`).
 * - AI 챗은 SSE 스트리밍이므로 `/api/v1/ai/chat` 응답을 SSE 이벤트 시퀀스로 모킹한다.
 * - dataset-manager.spec.ts 의 SSE/세션/패널 헬퍼 패턴을 재사용한다.
 */

import type { Page } from '@playwright/test';

import { createDatasetDetail } from '../../factories/dataset.factory';
import { mockApi } from '../../fixtures/api-mock';
import { expect, test } from '../../fixtures/auth.fixture';

const DATASET_ID = 42;

/** SSE 이벤트 직렬화 — "data: {json}\n\n" 형태로 변환 */
function sseEvent(data: Record<string, unknown>): string {
  return `data: ${JSON.stringify(data)}\n\n`;
}

/** show_dataset_files tool_use 를 담은 assistant 응답 SSE 이벤트. */
const SHOW_FILES_EVENTS = [
  sseEvent({ type: 'init', sessionId: 'dataset-files-session-1' }),
  sseEvent({
    type: 'tool_use',
    toolName: 'mcp__firehub__show_dataset_files',
    input: { datasetId: DATASET_ID },
    status: 'started',
  }),
  sseEvent({
    type: 'tool_result',
    toolName: 'mcp__firehub__show_dataset_files',
    result: JSON.stringify({ displayed: true, datasetId: DATASET_ID }),
    status: 'completed',
  }),
  sseEvent({ type: 'done', inputTokens: 120 }),
];

/** /api/v1/ai/chat SSE 모킹 — 어떤 POST든 show_dataset_files 응답을 반환한다. */
async function mockChatShowFiles(page: Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/chat',
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      await route.fulfill({
        status: 200,
        headers: {
          'Content-Type': 'text/event-stream',
          'Cache-Control': 'no-cache',
          Connection: 'keep-alive',
        },
        body: SHOW_FILES_EVENTS.join(''),
      });
    },
  );
}

/** AI 세션 목록/생성 API 모킹 (dataset-manager.spec.ts 패턴). */
async function mockAiSessions(page: Page) {
  await page.route(
    (url) => url.pathname === '/api/v1/ai/sessions',
    (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          id: 1,
          sessionId: 'dataset-files-session-1',
          title: null,
          createdAt: '2026-07-20T00:00:00Z',
          updatedAt: '2026-07-20T00:00:00Z',
        }),
      });
    },
  );
}

/** AI 챗 사이드 패널을 연다(dataset-manager.spec.ts 패턴). */
async function openChatPanel(page: Page) {
  await page.goto('/', { waitUntil: 'commit' });
  await page.getByText('AI 어시스턴트').first().click();
  await page.getByPlaceholder('메시지를 입력하세요...').waitFor({ state: 'visible', timeout: 5000 });
}

/** FILE 데이터셋 상세(위젯 제목용) 모킹. */
async function mockDatasetMeta(page: Page) {
  const detail = createDatasetDetail({
    id: DATASET_ID,
    name: '장비 학습 데이터',
    storageType: 'FILE',
    originType: 'SOURCE',
    columns: [],
    rowCount: null,
  });
  await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}`, detail);
}

test.describe('AI 챗 show_dataset_files 위젯', () => {
  test('FILE 데이터셋 파일 목록을 인라인 카드로 렌더한다', { tag: '@smoke' }, async ({
    authenticatedPage: page,
  }) => {
    await mockChatShowFiles(page);
    await mockAiSessions(page);
    await mockDatasetMeta(page);

    // 파일 목록 mock — 표시명은 prefix 제외 상대경로(S3 방식), 폴더 구조 보존.
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects`, {
      objects: [
        { key: 'equip/보고서.md', name: '보고서.md', size: 2048, lastModified: '2026-07-20T00:00:00Z' },
        { key: 'equip/2026/photo.jpg', name: '2026/photo.jpg', size: 1048576, lastModified: null },
      ],
      nextToken: null,
      hasMore: false,
    });

    await openChatPanel(page);

    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('장비 학습 데이터 파일 목록 보여줘');
    await chatInput.press('Enter');

    // 카드 제목 = 데이터셋 이름(메타 조회 결과). exact 로 위젯 제목만 스코프(사용자 메시지 문장과 구분).
    await expect(page.getByText('장비 학습 데이터', { exact: true })).toBeVisible({ timeout: 10_000 });
    // 표시명 = prefix 제외 상대경로. 데이터셋 prefix(equip/)는 숨긴다.
    await expect(page.getByText('보고서.md', { exact: true })).toBeVisible();
    await expect(page.getByText('2026/photo.jpg', { exact: true })).toBeVisible();
    await expect(page.getByText('equip/2026/photo.jpg')).toHaveCount(0);
    // 크기가 사람이 읽는 단위로 표기된다.
    await expect(page.getByText('2.0 KB')).toBeVisible();
    await expect(page.getByText('1.0 MB')).toBeVisible();
  });

  test('파일 클릭 시 presigned GET URL을 발급해 새 탭으로 연다', async ({
    authenticatedPage: page,
  }) => {
    await mockChatShowFiles(page);
    await mockAiSessions(page);
    await mockDatasetMeta(page);
    await mockApi(page, 'GET', `/api/v1/datasets/${DATASET_ID}/objects`, {
      objects: [{ key: 'equip/report.md', name: 'report.md', size: 2048, lastModified: null }],
      nextToken: null,
      hasMore: false,
    });

    // presigned 발급 mock — 요청 key 캡처 후 다운로드용 URL 반환.
    let requestedKey: string | null = null;
    await page.route(
      (u) => u.pathname === `/api/v1/datasets/${DATASET_ID}/objects/url`,
      (route) => {
        requestedKey = new URL(route.request().url()).searchParams.get('key');
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ url: 'https://example.com/download/report.md', expiresInSeconds: 300 }),
        });
      },
    );
    // 새 탭이 이동할 외부(MinIO 대체) URL — 컨텍스트 레벨 라우트로 팝업까지 커버.
    await page.context().route('https://example.com/**', (route) =>
      route.fulfill({ status: 200, contentType: 'text/plain', body: 'file-bytes' }),
    );

    await openChatPanel(page);
    const chatInput = page.getByPlaceholder('메시지를 입력하세요...');
    await chatInput.fill('파일 목록 보여줘');
    await chatInput.press('Enter');

    await expect(page.getByRole('button', { name: /report\.md/ })).toBeVisible({ timeout: 10_000 });

    // 파일(버튼) 클릭 → 새 탭(popup)이 열리고 presigned URL로 이동한다.
    const popupPromise = page.waitForEvent('popup');
    await page.getByRole('button', { name: /report\.md/ }).click();
    const popup = await popupPromise;
    await expect(popup).toHaveURL('https://example.com/download/report.md');

    // 발급 요청이 해당 오브젝트의 전체 키로 전달됐는지 검증.
    expect(requestedKey).toBe('equip/report.md');
  });
});
