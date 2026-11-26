/**
 * 리포트 뷰어 뒤로 가기 목적지 — 오류일 때는 캐시 HTML 을 목적지 판단에 쓰지 않는다 (WD-1177 리뷰 후속 ①)
 *
 * 새 뷰어 탭(앱 안 기록 없음)에서 캐시 HTML 을 갖고 있다가 재조회가 404 로 실패하면, 화면은 오류 안내인데
 * `htmlResponse !== undefined` 만 보면 캐시 데이터를 보고 목적지가 「작업 상세」로 잡힌다 — 그 안내 화면으로
 * 되돌아가게 된다. 캐시는 남아 있으니 뒤로 가기는 여전히 눌려야 하고, 이름만 안전한 「리포트 목록」이어야 한다.
 *
 * 이 시나리오는 앱 안 기록이 남는 E2E 에서 재현되지 않는다(기록이 있으면 라벨이 「뒤로」가 된다)라 단위 테스트로 본다.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { proactiveApi } from '../../api/proactive';
import ReportViewerPage from './ReportViewerPage';

/** axios.isAxiosError 와 response.status 로 isNotFoundError 가 참이 되는 404 오류 */
const notFoundError = {
  isAxiosError: true,
  response: { status: 404, data: { status: 404, error: 'Not Found', message: '리포트를 찾을 수 없습니다.' } },
} as never;

const CACHED_HTML = '<html><body>리포트 본문</body></html>';

/** 뷰어를 앱 안 기록 없이(첫 화면처럼) 렌더링한다 — useJobGoBack 의 라벨이 목적지 이름이 되는 조건 */
function renderViewer(client: QueryClient) {
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/ai-insights/jobs/1/executions/5/report']}>
        <Routes>
          <Route path="/ai-insights/jobs/:jobId/executions/:executionId/report" element={<ReportViewerPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ReportViewerPage 뒤로 가기 목적지 (WD-1177 리뷰 후속)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('조회 성공 때는 「작업 상세」, 재조회가 오류면 캐시와 무관하게 「리포트 목록」을 알린다', async () => {
    const getHtml = vi.spyOn(proactiveApi, 'getExecutionHtml').mockResolvedValue({ data: CACHED_HTML } as never);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderViewer(client);

    // 처음 조회 성공 — 캐시 HTML 이 있고 목적지를 읽을 수 있으니 작업 상세
    expect(await screen.findByRole('button', { name: '작업 상세' })).toBeInTheDocument();
    expect(await screen.findByTitle('리포트')).toBeInTheDocument();

    // 재조회가 404 로 실패 — 데이터(캐시)는 남아 있지만 오류가 있으니 목적지를 캐시로 잡지 않는다
    getHtml.mockRejectedValue(notFoundError);
    client.invalidateQueries();

    expect(await screen.findByRole('button', { name: '리포트 목록' })).toBeInTheDocument();
    // 캐시를 보고 다시 「작업 상세」로 되돌아가지 않는다
    expect(screen.queryByRole('button', { name: '작업 상세' })).not.toBeInTheDocument();
    // 캐시 HTML 은 그대로 본문이다(403 밖 오류는 캐시가 이긴다) — 본문과 뒤로 가기 둘 다 죽지 않는다
    expect(await screen.findByTitle('리포트')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '리포트 목록' })).toBeEnabled();
  });

  it('조회가 오류로 시작해 캐시도 없으면 처음부터 「리포트 목록」이다', async () => {
    vi.spyOn(proactiveApi, 'getExecutionHtml').mockRejectedValue(notFoundError);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderViewer(client);

    expect(await screen.findByRole('button', { name: '리포트 목록' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '작업 상세' })).not.toBeInTheDocument();
    // 캐시가 없으면 오류 안내를 그리며, 안내 아래 돌아가기 버튼이 눌린다(목적지를 모를 때도 잠그지 않는다)
    expect(await screen.findByRole('button', { name: '돌아가기' })).toBeInTheDocument();
  });
});