/**
 * PDF 라우트 통합 테스트
 *
 * POST /agent/html-to-pdf 엔드포인트에 대한 테스트.
 * Puppeteer는 vi.mock으로 대체하여 실제 브라우저를 띄우지 않는다.
 *
 * 주의: vi.mock 팩토리는 파일 최상단으로 호이스팅되므로 외부 변수를 참조할 수 없다.
 * 대신 vi.hoisted()로 미리 선언하거나 팩토리 내부에서 직접 vi.fn()을 사용해야 한다.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';

// vi.hoisted()로 mock 함수를 팩토리 호이스팅 전에 선언
const {
  mockPdf,
  mockSetContent,
  mockWaitForNetworkIdle,
  mockClose,
  mockNewPage,
  mockLaunch,
  mockSetRequestInterception,
  mockSetJavaScriptEnabled,
  mockOn,
  mockUrl,
  mockMainFrame,
  mockEvalMetaRefresh,
  createMockPage,
} = vi.hoisted(() => {
  const mockPdf = vi.fn();
  const mockSetContent = vi.fn();
  // WD-790: Puppeteer 25 에서 setContent 의 networkidle0 대신 따로 거는 네트워크 유휴 대기
  const mockWaitForNetworkIdle = vi.fn();
  const mockClose = vi.fn();
  // WD-617: 요청 가로채기·JS 비활성화 호출을 검증하기 위한 mock
  const mockSetRequestInterception = vi.fn();
  const mockSetJavaScriptEnabled = vi.fn();
  const mockOn = vi.fn();
  // WD-617: 메인 프레임 이동 감지용 — 기본은 이동 없이 about:blank 에 머문 상태
  const mockUrl = vi.fn();
  const mockMainFrame = {};
  // WD-790: 문서 안 meta refresh 개수 조회($$eval) — 기본은 0개
  const mockEvalMetaRefresh = vi.fn();
  // 위 mock 함수들로 page 객체를 만든다 — 초기 선언과 beforeEach 재설정이 같은 모양을 쓰도록 한 곳에 둔다
  const createMockPage = () => ({
    setContent: mockSetContent,
    waitForNetworkIdle: mockWaitForNetworkIdle,
    pdf: mockPdf,
    close: mockClose,
    setRequestInterception: mockSetRequestInterception,
    setJavaScriptEnabled: mockSetJavaScriptEnabled,
    on: mockOn,
    url: mockUrl,
    mainFrame: () => mockMainFrame,
    $$eval: mockEvalMetaRefresh,
  });
  const mockNewPage = vi.fn().mockResolvedValue(createMockPage());
  const mockBrowser = {
    newPage: mockNewPage,
    connected: true,
  };
  const mockLaunch = vi.fn().mockResolvedValue(mockBrowser);
  return {
    mockPdf,
    mockSetContent,
    mockWaitForNetworkIdle,
    mockClose,
    mockNewPage,
    mockLaunch,
    mockSetRequestInterception,
    mockSetJavaScriptEnabled,
    mockOn,
    mockUrl,
    mockMainFrame,
    mockEvalMetaRefresh,
    createMockPage,
  };
});

// Puppeteer 모킹 — 실제 headless Chrome 실행 방지
vi.mock('puppeteer', () => ({
  default: {
    launch: mockLaunch,
  },
}));

// 모킹 설정 후 라우터를 동적으로 임포트
import pdfRouter, { RENDER_TIMEOUT_MS } from './pdf.js';

const VALID_TOKEN = 'test-internal-token-pdf';

/** Express 앱 생성 헬퍼 */
function createApp() {
  const app = express();
  app.use('/agent', pdfRouter);
  return app;
}

/**
 * HTTP 요청 헬퍼
 * classify.test.ts 패턴과 동일하게 fetch + listen(0)으로 임의 포트 사용
 */
async function makeRequest(
  app: express.Express,
  body?: Record<string, unknown>,
  headers?: Record<string, string>,
): Promise<{ status: number; body: unknown; headers: Record<string, string> }> {
  return new Promise((resolve, reject) => {
    const server = app.listen(0, () => {
      const addr = server.address();
      if (!addr || typeof addr === 'string') {
        server.close();
        reject(new Error('Failed to get server address'));
        return;
      }
      const port = addr.port;
      const url = `http://localhost:${port}/agent/html-to-pdf`;
      const options: RequestInit = {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...headers,
        },
        body: body ? JSON.stringify(body) : undefined,
      };
      fetch(url, options)
        .then(async (res) => {
          const contentType = res.headers.get('content-type') ?? '';
          let parsed: unknown;
          if (contentType.includes('application/json')) {
            parsed = await res.json();
          } else {
            parsed = await res.text();
          }
          const resHeaders: Record<string, string> = {};
          res.headers.forEach((value, key) => {
            resHeaders[key] = value;
          });
          server.close();
          resolve({ status: res.status, body: parsed, headers: resHeaders });
        })
        .catch((err) => {
          server.close();
          reject(err);
        });
    });
  });
}

describe('POST /agent/html-to-pdf', () => {
  beforeEach(() => {
    // 각 테스트 전 환경변수 설정 및 mock 초기화
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
    vi.clearAllMocks();

    // Puppeteer mock 기본값 재설정
    mockUrl.mockReturnValue('about:blank');
    mockEvalMetaRefresh.mockResolvedValue(0);
    mockNewPage.mockResolvedValue(createMockPage());
    mockLaunch.mockResolvedValue({ newPage: mockNewPage, connected: true });
    mockSetContent.mockResolvedValue(undefined);
    mockClose.mockResolvedValue(undefined);
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
  });

  /**
   * load(setContent)가 loadMs 만큼 걸린 것처럼 시계를 움직이며 PDF 요청을 한 번 보내고,
   * 응답과 waitForNetworkIdle 에 넘어간 timeout 을 돌려준다(WD-790 단일 렌더 기한 시험용).
   * Date.now 를 직접 흉내 내므로 끝나면 반드시 원래대로 되돌린다.
   */
  async function renderWithLoadTaking(loadMs: number) {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));
    let now = 1_000_000;
    const nowSpy = vi.spyOn(Date, 'now').mockImplementation(() => now);
    mockSetContent.mockImplementation(async () => {
      now += loadMs;
    });
    try {
      const res = await makeRequest(createApp(), { html: '<p>x</p>' }, { Authorization: `Internal ${VALID_TOKEN}` });
      const idleTimeout = mockWaitForNetworkIdle.mock.calls[0][0].timeout as number;
      return { res, idleTimeout };
    } finally {
      nowSpy.mockRestore();
    }
  }

  // 시나리오 1: Authorization 헤더 누락 → 401
  it('should return 401 when Authorization header is missing', async () => {
    const app = createApp();
    const res = await makeRequest(app, { html: '<h1>Hello</h1>' });
    expect(res.status).toBe(401);
  });

  // 시나리오 2: 잘못된 Internal 토큰 → 401
  it('should return 401 for invalid Internal token', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      { html: '<h1>Hello</h1>' },
      { Authorization: 'Internal wrong-token' },
    );
    expect(res.status).toBe(401);
  });

  // 시나리오 3: html 필드 누락 → 400
  it('should return 400 when html field is missing', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      {},
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });

  // 시나리오 4: html이 빈 문자열 → 400
  it('should return 400 when html is empty string', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      { html: '' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error', 'html field is required');
  });

  // 시나리오 5: 정상 요청 → 200 + application/pdf
  it('should return 200 with PDF binary on valid request', async () => {
    // PDF 바이너리 시뮬레이션
    const fakePdfBuffer = Buffer.from('%PDF-1.4 fake content');
    mockPdf.mockResolvedValue(fakePdfBuffer);

    const app = createApp();
    const res = await makeRequest(
      app,
      { html: '<html><body><h1>Report</h1></body></html>' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    expect(res.headers['content-type']).toContain('application/pdf');
    // page.setContent와 page.pdf가 호출되었는지 확인
    expect(mockSetContent).toHaveBeenCalledOnce();
    // 예전 networkidle0 과 같은 조건(진행 중 요청 0건으로 500ms)으로 렌더 안정을 기다린 뒤 PDF 를 만든다(WD-790)
    expect(mockWaitForNetworkIdle).toHaveBeenCalledWith(expect.objectContaining({ idleTime: 500, concurrency: 0 }));
    expect(mockWaitForNetworkIdle.mock.invocationCallOrder[0]).toBeGreaterThan(mockSetContent.mock.invocationCallOrder[0]);
    expect(mockWaitForNetworkIdle.mock.invocationCallOrder[0]).toBeLessThan(mockPdf.mock.invocationCallOrder[0]);
    expect(mockPdf).toHaveBeenCalledOnce();
    // 페이지는 반드시 닫혀야 함 (finally 블록)
    expect(mockClose).toHaveBeenCalledOnce();
  });

  // 시나리오 5-1 (WD-790): load 와 네트워크 유휴 대기가 각각 30초씩(최악 60초 — api PDF_TIMEOUT 60초에 닿음) 쓰지 않고
  // 하나의 30초 기한을 나눠 쓴다 — 유휴 대기의 timeout 은 load 가 쓰고 남은 시간이어야 한다
  it('should give waitForNetworkIdle only the time left from the single render deadline', async () => {
    // load(setContent)가 12초 걸린 상황
    const { res, idleTimeout } = await renderWithLoadTaking(12_000);
    expect(res.status).toBe(200);

    expect(RENDER_TIMEOUT_MS).toBeLessThanOrEqual(30000);
    expect(mockSetContent).toHaveBeenCalledWith(expect.any(String), expect.objectContaining({ timeout: RENDER_TIMEOUT_MS }));
    // 남은 예산 = 30초 - load 에 쓴 12초
    expect(idleTimeout).toBe(RENDER_TIMEOUT_MS - 12_000);
  });

  // 시나리오 5-2 (WD-790): load 가 기한을 다 써도 유휴 대기 timeout 은 0(Puppeteer 에서 무제한)이 아닌 양수여야 한다
  it('should never pass a non-positive (unlimited) timeout to waitForNetworkIdle', async () => {
    // load 가 기한을 5초 넘겨 쓴 상황
    const { idleTimeout } = await renderWithLoadTaking(RENDER_TIMEOUT_MS + 5_000);
    expect(idleTimeout).toBeGreaterThan(0);
  });

  // 시나리오 6: format 옵션 전달 → pdf()에 전달되는지 확인
  it('should pass format and landscape options to pdf()', async () => {
    const fakePdfBuffer = Buffer.from('%PDF-1.4 fake');
    mockPdf.mockResolvedValue(fakePdfBuffer);

    const app = createApp();
    await makeRequest(
      app,
      { html: '<p>test</p>', format: 'Letter', landscape: true },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(mockPdf).toHaveBeenCalledWith(
      expect.objectContaining({
        format: 'Letter',
        landscape: true,
        printBackground: true,
      }),
    );
  });

  // 시나리오 7: Puppeteer 오류 → 500
  it('should return 500 when puppeteer throws an error', async () => {
    mockPdf.mockRejectedValue(new Error('Puppeteer rendering failed'));

    const app = createApp();
    const res = await makeRequest(
      app,
      { html: '<h1>Test</h1>' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(500);
    expect(res.body).toHaveProperty('error', 'PDF generation failed');
    expect(res.body).toHaveProperty('details');
    // 오류가 나도 page.close()는 finally 블록에서 실행되어야 함
    expect(mockClose).toHaveBeenCalledOnce();
  });

  // 시나리오 8: 기본 format은 A4
  it('should use A4 format by default when format is not specified', async () => {
    const fakePdfBuffer = Buffer.from('%PDF-1.4 fake');
    mockPdf.mockResolvedValue(fakePdfBuffer);

    const app = createApp();
    await makeRequest(
      app,
      { html: '<p>default format</p>' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(mockPdf).toHaveBeenCalledWith(
      expect.objectContaining({
        format: 'A4',
        landscape: false,
      }),
    );
  });

  // 시나리오 9 (WD-617): 렌더 전에 요청 가로채기·JS 비활성화를 켜고, 네트워크 요청은 abort 한다
  it('should block network requests before rendering (SSRF guard)', async () => {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));

    const app = createApp();
    await makeRequest(
      app,
      { html: '<img src="http://169.254.169.254/latest/meta-data/">' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(mockSetRequestInterception).toHaveBeenCalledWith(true);
    expect(mockSetJavaScriptEnabled).toHaveBeenCalledWith(false);
    // 가로채기·JS 비활성화는 HTML 을 넣기 전에 켜져야 한다
    const contentOrder = mockSetContent.mock.invocationCallOrder[0];
    expect(mockSetRequestInterception.mock.invocationCallOrder[0]).toBeLessThan(contentOrder);
    expect(mockSetJavaScriptEnabled.mock.invocationCallOrder[0]).toBeLessThan(contentOrder);

    // 등록된 request 핸들러를 꺼내 판정 결과가 continue/abort 로 이어지는지 확인
    const handler = mockOn.mock.calls.find(([event]) => event === 'request')?.[1] as (
      req: unknown,
    ) => void;
    expect(handler).toBeTypeOf('function');
    const fakeRequest = (url: string, navigation = false, frame: unknown = {}) => ({
      url: () => url,
      isNavigationRequest: () => navigation,
      frame: () => frame,
      continue: vi.fn().mockResolvedValue(undefined),
      abort: vi.fn().mockResolvedValue(undefined),
    });

    const blocked = fakeRequest('http://169.254.169.254/latest/meta-data/');
    handler(blocked);
    expect(blocked.abort).toHaveBeenCalledWith('blockedbyclient');
    expect(blocked.continue).not.toHaveBeenCalled();

    const inline = fakeRequest('data:image/png;base64,iVBORw0KGgo=');
    handler(inline);
    expect(inline.continue).toHaveBeenCalledOnce();
    expect(inline.abort).not.toHaveBeenCalled();

    // 하위 프레임(iframe)의 data: 이동은 허용하고, 메인 프레임 이동은 data: 라도 막는다
    const childNav = fakeRequest('data:text/html,<p>x</p>', true, {});
    handler(childNav);
    expect(childNav.continue).toHaveBeenCalledOnce();
    const mainNav = fakeRequest('data:text/html,<p>x</p>', true, mockMainFrame);
    handler(mainNav);
    expect(mainNav.abort).toHaveBeenCalledWith('blockedbyclient');
    expect(mainNav.continue).not.toHaveBeenCalled();
  });

  // 시나리오 10 (WD-617): 메인 프레임이 다른 문서로 이동하려 했으면 빈 오류 페이지 PDF 대신 422
  it('should return 422 instead of an error-page PDF when the main frame tries to navigate', async () => {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));
    // setContent 도중 meta refresh 가 메인 프레임 이동 요청을 일으킨 상황을 흉내 낸다
    mockSetContent.mockImplementation(async () => {
      const handler = mockOn.mock.calls.find(([event]) => event === 'request')?.[1] as (
        req: unknown,
      ) => void;
      handler({
        url: () => 'http://127.0.0.1:5010/refresh',
        isNavigationRequest: () => true,
        frame: () => mockMainFrame,
        continue: vi.fn().mockResolvedValue(undefined),
        abort: vi.fn().mockResolvedValue(undefined),
      });
    });
    mockUrl.mockReturnValue('chrome-error://chromewebdata/');

    const app = createApp();
    const res = await makeRequest(
      app,
      { html: '<meta http-equiv="refresh" content="0;url=http://127.0.0.1:5010/refresh">' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(422);
    expect(res.body).toHaveProperty('error', 'PDF generation rejected');
    expect(mockPdf).not.toHaveBeenCalled();
    // 거부해도 페이지는 닫혀야 한다
    expect(mockClose).toHaveBeenCalledOnce();
  });

  // 시나리오 11 (WD-790): 갓 뜬 렌더러에서는 meta refresh 이동 요청이 유휴 대기보다 늦게 나온다 —
  // 판정 시점에 이동 요청이 아직 없어도 문서에 meta refresh 가 있으면 PDF 를 찍지 않고 422
  it('should return 422 when the document has a meta refresh even before its navigation request fires', async () => {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));
    mockEvalMetaRefresh.mockResolvedValue(1);

    const app = createApp();
    const res = await makeRequest(
      app,
      { html: '<meta http-equiv="refresh" content="0;url=https://example.com/"><h1>x</h1>' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(422);
    expect(res.body).toHaveProperty('error', 'PDF generation rejected');
    // 대소문자 무시 선택자로 meta refresh 를 찾는다(Chrome 처럼 Refresh·REFRESH 도 같다)
    expect(mockEvalMetaRefresh).toHaveBeenCalledWith('meta[http-equiv="refresh" i]', expect.any(Function));
    expect(mockPdf).not.toHaveBeenCalled();
    expect(mockClose).toHaveBeenCalledOnce();
  });

  // 시나리오 12 (WD-790): 판정을 통과한 뒤 PDF 를 찍는 사이에 메인 프레임 이동이 일어나면 찍힌 PDF 를 버리고 422
  it('should discard the PDF and return 422 when the main frame navigates while printing', async () => {
    mockPdf.mockImplementation(async () => {
      const handler = mockOn.mock.calls.find(([event]) => event === 'request')?.[1] as (
        req: unknown,
      ) => void;
      handler({
        url: () => 'https://example.com/',
        isNavigationRequest: () => true,
        frame: () => mockMainFrame,
        continue: vi.fn().mockResolvedValue(undefined),
        abort: vi.fn().mockResolvedValue(undefined),
      });
      return Buffer.from('%PDF-1.4 error page');
    });

    const app = createApp();
    const res = await makeRequest(
      app,
      { html: '<h1>x</h1>' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(422);
    expect(res.body).toHaveProperty('error', 'PDF generation rejected');
    expect(mockClose).toHaveBeenCalledOnce();
  });
});
