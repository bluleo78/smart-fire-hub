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
  mockCdpSend,
  mockCreateCDPSession,
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
  // WD-790: 문서 안 meta refresh 조회($$eval) — WD-799 부터 각 meta 의 content 배열을 돌려준다. 기본은 meta 없음([])
  const mockEvalMetaRefresh = vi.fn();
  // WD-799 리뷰 반영: 메인 프레임 문서 교체를 loaderId 로 판정하는 별도 CDP 세션 — send('Page.getFrameTree') 만 쓴다
  const mockCdpSend = vi.fn();
  const mockCreateCDPSession = vi.fn();
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
    createCDPSession: mockCreateCDPSession,
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
    mockCdpSend,
    mockCreateCDPSession,
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
import pdfRouter, { RENDER_TIMEOUT_MS, TOTAL_TIMEOUT_MS, isRefreshWithinRender } from './pdf.js';

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
    mockEvalMetaRefresh.mockResolvedValue([]);
    mockNewPage.mockResolvedValue(createMockPage());
    mockLaunch.mockResolvedValue({ newPage: mockNewPage, connected: true });
    mockSetContent.mockResolvedValue(undefined);
    mockClose.mockResolvedValue(undefined);
    // 기본은 문서 교체 없음 — 메인 프레임 loaderId 가 처음 값(L0) 그대로
    setMainFrameLoaderId('L0');
    mockCreateCDPSession.mockResolvedValue({ send: mockCdpSend });
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
  });

  /** 지금부터 CDP Page.getFrameTree 가 돌려줄 메인 프레임 loaderId 를 정한다 — 값이 바뀌면 새 문서가 커밋된 것이다 */
  function setMainFrameLoaderId(loaderId: string) {
    mockCdpSend.mockImplementation(async (method: string) => {
      if (method === 'Page.getFrameTree') return { frameTree: { frame: { id: 'MAIN', loaderId } } };
      throw new Error(`unexpected CDP method ${method}`);
    });
  }

  /**
   * load(setContent)가 loadMs 만큼 걸린 것처럼 시계를 움직이며 PDF 요청을 한 번 보내고,
   * 응답과 waitForNetworkIdle·page.pdf 에 넘어간 timeout 을 돌려준다(WD-790 단일 렌더 기한 시험용).
   * 유휴 대기를 건너뛴 경우(WD-799 — 남은 시간 < 500ms) idleTimeout 은 undefined 다.
   * Date.now 를 직접 흉내 내므로 끝나면 반드시 원래대로 되돌린다.
   */
  async function renderWithLoadTaking(loadMs: number, opts: { prepMs?: number; metas?: (string | null)[]; replaceDocumentDuringLoad?: boolean } = {}) {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));
    if (opts.metas) mockEvalMetaRefresh.mockResolvedValue(opts.metas);
    let now = 1_000_000;
    const nowSpy = vi.spyOn(Date, 'now').mockImplementation(() => now);
    // 브라우저 준비(newPage)에 prepMs 가 걸린 것처럼 — WD-799: 전체 기한은 처리기 시작부터 잰다
    mockNewPage.mockImplementation(async () => {
      now += opts.prepMs ?? 0;
      return createMockPage();
    });
    mockSetContent.mockImplementation(async () => {
      now += loadMs;
      // load 도중 메인 프레임에 새 문서가 커밋된 상황 — loaderId 만 바뀐다
      if (opts.replaceDocumentDuringLoad) setMainFrameLoaderId('L1');
    });
    try {
      const res = await makeRequest(createApp(), { html: '<p>x</p>' }, { Authorization: `Internal ${VALID_TOKEN}` });
      const idleTimeout = mockWaitForNetworkIdle.mock.calls[0]?.[0]?.timeout as number | undefined;
      const pdfTimeout = mockPdf.mock.calls[0]?.[0]?.timeout as number | undefined;
      const setContentTimeout = mockSetContent.mock.calls[0]?.[1]?.timeout as number | undefined;
      return { res, idleTimeout, pdfTimeout, setContentTimeout };
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

  // 시나리오 5-2 (WD-790 → WD-799): load 가 기한을 다 쓰면 유휴 대기는 성공할 수 없으므로 건너뛰고(타임아웃 500 대신)
  // PDF 로 간다 — 0(Puppeteer 에서 무제한) timeout 으로 부르는 일도 없다
  it('should skip waitForNetworkIdle (never with an unlimited timeout) when load used up the render deadline', async () => {
    // load 가 기한을 5초 넘겨 쓴 상황
    const { res, idleTimeout } = await renderWithLoadTaking(RENDER_TIMEOUT_MS + 5_000);
    expect(res.status).toBe(200);
    expect(mockWaitForNetworkIdle).not.toHaveBeenCalled();
    expect(idleTimeout).toBeUndefined();
    expect(mockPdf).toHaveBeenCalledOnce();
  });

  // 시나리오 5-3 (WD-799): load 뒤 남은 시간이 유휴 판정 시간(500ms)보다 짧으면 유휴 대기를 부르지 않고 바로 PDF 로 간다
  it('should skip waitForNetworkIdle when less than the idle time (500ms) is left after load', async () => {
    // 기한 직전(200ms 남음)까지 load 가 걸린 상황
    const { res } = await renderWithLoadTaking(RENDER_TIMEOUT_MS - 200);
    expect(res.status).toBe(200);
    expect(mockWaitForNetworkIdle).not.toHaveBeenCalled();
    expect(mockPdf).toHaveBeenCalledOnce();
  });

  // 시나리오 5-4 (WD-799): 남은 시간이 정확히 500ms 이면 유휴 대기를 부르고, timeout 은 그 남은 시간(양수)이다
  it('should still wait for network idle when exactly the idle time is left', async () => {
    const { idleTimeout } = await renderWithLoadTaking(RENDER_TIMEOUT_MS - 500);
    expect(idleTimeout).toBe(500);
  });

  // 시나리오 5-5 (WD-799): page.pdf() 는 기본 30초를 따로 쓰지 않고 전체 기한(50초 < api 60초)에서 남은 시간만 쓴다
  it('should give page.pdf only the time left from the total deadline', async () => {
    // api PdfExportService PDF_TIMEOUT(60초)보다 짧아야 이쪽 오류가 api 로 전달된다
    expect(TOTAL_TIMEOUT_MS).toBeLessThan(60_000);
    expect(TOTAL_TIMEOUT_MS).toBeGreaterThan(RENDER_TIMEOUT_MS);
    const { pdfTimeout } = await renderWithLoadTaking(12_000);
    expect(pdfTimeout).toBe(TOTAL_TIMEOUT_MS - 12_000);
  });

  // 시나리오 5-6 (WD-799): 렌더가 전체 기한을 넘겨도 page.pdf timeout 은 0(무제한)이 아닌 양수다
  it('should never pass a non-positive (unlimited) timeout to page.pdf', async () => {
    const { pdfTimeout } = await renderWithLoadTaking(TOTAL_TIMEOUT_MS + 5_000);
    expect(pdfTimeout).toBeGreaterThan(0);
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
    mockEvalMetaRefresh.mockResolvedValue(['0;url=https://example.com/']);

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

  /** 등록된 request 핸들러로 메인 프레임 이동 요청 하나를 흘려 이동 표시(mainFrameNavigationBlocked)를 세운다 */
  function fireMainFrameNavigation(url: string) {
    const handler = mockOn.mock.calls.find(([event]) => event === 'request')?.[1] as (req: unknown) => void;
    handler({
      url: () => url,
      isNavigationRequest: () => true,
      frame: () => mockMainFrame,
      continue: vi.fn().mockResolvedValue(undefined),
      abort: vi.fn().mockResolvedValue(undefined),
    });
  }

  // 시나리오 13 (WD-799): 판정 직전에 이동이 커밋돼 $$eval 이 실행 컨텍스트 소멸로 던지면, 이동 표시가 서 있으므로 500 이 아닌 422
  it('should return 422 (not 500) when $$eval throws because a navigation committed right before it', async () => {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));
    mockEvalMetaRefresh.mockImplementation(async () => {
      // $$eval 을 보내는 사이 meta refresh 이동이 일어난 상황 — 가로채기가 먼저 이동 요청을 보고, 평가는 컨텍스트 소멸로 실패한다
      fireMainFrameNavigation('https://example.com/');
      throw new Error('Execution context was destroyed, most likely because of a navigation.');
    });

    const res = await makeRequest(createApp(), { html: '<h1>x</h1>' }, { Authorization: `Internal ${VALID_TOKEN}` });

    expect(res.status).toBe(422);
    expect(res.body).toHaveProperty('error', 'PDF generation rejected');
    expect(mockPdf).not.toHaveBeenCalled();
    expect(mockClose).toHaveBeenCalledOnce();
  });

  // 시나리오 14 (WD-799): 이동 표시 없이 $$eval 이 던지면 원인이 다른 진짜 오류이므로 422 로 덮지 않고 500
  it('should return 500 when $$eval throws without any navigation', async () => {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));
    mockEvalMetaRefresh.mockRejectedValue(new Error('Protocol error: Target closed'));

    const res = await makeRequest(createApp(), { html: '<h1>x</h1>' }, { Authorization: `Internal ${VALID_TOKEN}` });

    expect(res.status).toBe(500);
    expect(res.body).toHaveProperty('error', 'PDF generation failed');
    expect(mockPdf).not.toHaveBeenCalled();
    expect(mockClose).toHaveBeenCalledOnce();
  });

  // 시나리오 15 (WD-799): 유휴 대기 도중 이미 이동이 일어났으면 meta 가 없어도 PDF 를 찍지 않고 422
  it('should return 422 without printing when the main frame navigated during the idle wait', async () => {
    mockWaitForNetworkIdle.mockImplementationOnce(async () => {
      fireMainFrameNavigation('https://example.com/');
    });

    const res = await makeRequest(createApp(), { html: '<h1>x</h1>' }, { Authorization: `Internal ${VALID_TOKEN}` });

    expect(res.status).toBe(422);
    expect(mockPdf).not.toHaveBeenCalled();
  });

  // 시나리오 16 (WD-799): 렌더 기한 안에 일어나지 않는 refresh(긴 지연·빈 값·숫자 아님)만 있으면 정상 PDF(200)
  it('should render a PDF when every meta refresh is outside the render deadline or ignored by Chrome', async () => {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));
    mockEvalMetaRefresh.mockResolvedValue(['3600', '', 'abc', null]);

    const res = await makeRequest(createApp(), { html: '<h1>x</h1>' }, { Authorization: `Internal ${VALID_TOKEN}` });

    expect(res.status).toBe(200);
    expect(mockPdf).toHaveBeenCalledOnce();
  });

  // 시나리오 17 (WD-799): 여러 meta 중 하나라도 기한 안에 일어나면 422
  it('should return 422 when any meta refresh fires within the render deadline', async () => {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));
    mockEvalMetaRefresh.mockResolvedValue(['3600', ' 5 ; url=https://example.com/']);

    const res = await makeRequest(createApp(), { html: '<h1>x</h1>' }, { Authorization: `Internal ${VALID_TOKEN}` });

    expect(res.status).toBe(422);
    expect(mockPdf).not.toHaveBeenCalled();
  });

  // ─── WD-799: 메인 프레임 문서 교체 판정은 loaderId(CDP 명령 응답)로 ───
  // 가로채기에 보이지 않는 교체(주소 없는 다시 읽기 등)는 loaderId 만 바뀐다 — 그것만으로 422 가 되는지 본다.

  // 시나리오 21: loaderId 는 setContent 보다 먼저 적어 둔다(그 뒤에 적으면 0초 다시 읽기 뒤의 값을 기준으로 삼는다)
  it('should record the main frame loaderId before setContent', async () => {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));

    const res = await makeRequest(createApp(), { html: '<h1>x</h1>' }, { Authorization: `Internal ${VALID_TOKEN}` });

    expect(res.status).toBe(200);
    expect(mockCdpSend).toHaveBeenCalledWith('Page.getFrameTree');
    expect(mockCdpSend.mock.invocationCallOrder[0]).toBeLessThan(mockSetContent.mock.invocationCallOrder[0]);
  });

  // 시나리오 22: 주소 없는 0초 refresh 가 setContent 도중 about:blank 를 다시 읽어 새 문서를 커밋 — 가로채기 요청도 없고
  // url 도 about:blank 그대로이며 meta 도 DOM 에서 사라지지만, loaderId 가 바뀌었으므로 422(빈 PDF 금지)
  it('should return 422 when the main frame loaderId changed during setContent', async () => {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));
    mockSetContent.mockImplementationOnce(async () => {
      setMainFrameLoaderId('L1');
    });

    const res = await makeRequest(createApp(), { html: '<h1>x</h1>' }, { Authorization: `Internal ${VALID_TOKEN}` });

    expect(res.status).toBe(422);
    expect(mockPdf).not.toHaveBeenCalled();
  });

  // 시나리오 23: 판정 직후 커밋돼 $$eval 이 새(빈) 문서에서 돌아 meta 를 못 찾아도, 그 뒤 loaderId 판정으로 422
  it('should return 422 when $$eval ran on a freshly committed empty document', async () => {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));
    mockEvalMetaRefresh.mockImplementationOnce(async () => {
      setMainFrameLoaderId('L1');
      return [];
    });

    const res = await makeRequest(createApp(), { html: '<h1>x</h1>' }, { Authorization: `Internal ${VALID_TOKEN}` });

    expect(res.status).toBe(422);
    expect(mockPdf).not.toHaveBeenCalled();
  });

  // 시나리오 24: $$eval 이 컨텍스트 소멸로 던졌고 가로채기 표시는 없지만 loaderId 가 바뀌었으면 500 이 아니라 422
  it('should return 422 when $$eval throws and only the loaderId shows the navigation', async () => {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));
    mockEvalMetaRefresh.mockImplementationOnce(async () => {
      setMainFrameLoaderId('L1');
      throw new Error('Execution context was destroyed, most likely because of a navigation.');
    });

    const res = await makeRequest(createApp(), { html: '<h1>x</h1>' }, { Authorization: `Internal ${VALID_TOKEN}` });

    expect(res.status).toBe(422);
    expect(mockPdf).not.toHaveBeenCalled();
  });

  // 시나리오 25: $$eval 이 던진 뒤 loaderId 조회마저 실패하면 원래 $$eval 오류를 500 으로 낸다
  it('should return 500 with the original $$eval error when the loaderId lookup also fails', async () => {
    mockEvalMetaRefresh.mockImplementationOnce(async () => {
      mockCdpSend.mockRejectedValue(new Error('Session closed'));
      throw new Error('Protocol error: Target closed');
    });

    const res = await makeRequest(createApp(), { html: '<h1>x</h1>' }, { Authorization: `Internal ${VALID_TOKEN}` });

    expect(res.status).toBe(500);
    expect(res.body).toHaveProperty('details', 'Protocol error: Target closed');
  });

  // 시나리오 25-1: 같은 문서 안 #앵커 이동처럼 url 만 바뀌고 loaderId·가로채기 표시가 그대로면 문서는 우리 HTML 그대로라 200
  it('should render a PDF when only the URL changes within the same document (loaderId unchanged)', async () => {
    mockPdf.mockResolvedValue(Buffer.from('%PDF-1.4 fake'));
    mockUrl.mockReturnValue('about:blank#section');

    const res = await makeRequest(createApp(), { html: '<h1>x</h1>' }, { Authorization: `Internal ${VALID_TOKEN}` });

    expect(res.status).toBe(200);
    expect(mockPdf).toHaveBeenCalledOnce();
  });

  // 시나리오 26: PDF 를 찍는 사이 새 문서가 커밋되면, page.pdf() 뒤 loaderId 판정으로 찍힌 PDF 를 버리고 422
  it('should discard the PDF when the loaderId changed while printing', async () => {
    mockPdf.mockImplementationOnce(async () => {
      setMainFrameLoaderId('L1');
      return Buffer.from('%PDF-1.4 blank');
    });

    const res = await makeRequest(createApp(), { html: '<h1>x</h1>' }, { Authorization: `Internal ${VALID_TOKEN}` });

    expect(res.status).toBe(422);
    expect(mockPdf).toHaveBeenCalledOnce();
  });

  // 시나리오 27: 유휴 대기를 건너뛴 경로(load 가 렌더 기한을 거의 다 씀)에서도 loaderId 판정을 거친다
  it('should still check the loaderId when the idle wait is skipped', async () => {
    const { res } = await renderWithLoadTaking(RENDER_TIMEOUT_MS - 200, { replaceDocumentDuringLoad: true });

    expect(mockWaitForNetworkIdle).not.toHaveBeenCalled();
    expect(res.status).toBe(422);
    expect(mockPdf).not.toHaveBeenCalled();
  });

  // ─── WD-799 리뷰 반영: 전체 기한은 처리기 시작부터 잰다 ───

  // 시나리오 28: 브라우저 준비(newPage)에 20초가 걸리면 렌더는 남은 30초, page.pdf 는 전체 50초에서 남은 시간만 쓴다
  it('should count browser preparation time against the total deadline', async () => {
    const { res, setContentTimeout, pdfTimeout } = await renderWithLoadTaking(5_000, { prepMs: 20_000 });

    expect(res.status).toBe(200);
    // 렌더 기한 = min(30초, 50초 - 준비 20초) = 30초
    expect(setContentTimeout).toBe(RENDER_TIMEOUT_MS);
    // page.pdf = 50초 - 준비 20초 - load 5초
    expect(pdfTimeout).toBe(TOTAL_TIMEOUT_MS - 20_000 - 5_000);
  });

  // 시나리오 29: 준비에 40초가 걸리면 렌더 기한은 30초가 아니라 전체 기한에서 남은 10초로 줄어든다
  it('should shrink the render budget to what is left of the total deadline', async () => {
    const { setContentTimeout, idleTimeout } = await renderWithLoadTaking(2_000, { prepMs: 40_000 });

    expect(setContentTimeout).toBe(TOTAL_TIMEOUT_MS - 40_000);
    // 유휴 대기는 줄어든 렌더 기한(10초)에서 load 2초를 뺀 나머지
    expect(idleTimeout).toBe(TOTAL_TIMEOUT_MS - 40_000 - 2_000);
  });

  // 시나리오 30: 준비만으로 전체 기한을 다 쓰면 렌더를 시작하지 않고 500
  it('should fail with 500 without rendering when preparation used up the total deadline', async () => {
    const { res } = await renderWithLoadTaking(0, { prepMs: TOTAL_TIMEOUT_MS + 1_000 });

    expect(res.status).toBe(500);
    expect(mockSetContent).not.toHaveBeenCalled();
    expect(mockPdf).not.toHaveBeenCalled();
    expect(mockClose).toHaveBeenCalledOnce();
  });

  // ─── WD-799 리뷰 반영: refresh 지연은 50초 통째가 아니라 렌더 시작 때 남은 시간과 비교한다 ───

  // 시나리오 31: 준비에 20초를 써 남은 시간이 30초면, 40초 refresh 는 이 요청 안에 일어나지 않으므로 통과(200)
  it('should allow a refresh whose delay exceeds the time left at render start', async () => {
    const { res } = await renderWithLoadTaking(1_000, { prepMs: 20_000, metas: ['40;url=https://example.com/'] });

    expect(res.status).toBe(200);
  });

  // 시나리오 32: 같은 상황에서 30초 refresh(남은 시간과 같음)는 거부(422)
  it('should reject a refresh whose delay fits in the time left at render start', async () => {
    const { res } = await renderWithLoadTaking(1_000, { prepMs: 20_000, metas: ['30;url=https://example.com/'] });

    expect(res.status).toBe(422);
    expect(mockPdf).not.toHaveBeenCalled();
  });

  // 시나리오 33: 남은 시간이 1초 미만이어도 0초 refresh 는 주소가 있든 없든 거부된다(올림·0 하한)
  it.each([['0;url=https://example.com/'], ['0']])('should always reject a 0s refresh (%j) even with almost no time left', async (meta) => {
    const { res } = await renderWithLoadTaking(0, { prepMs: TOTAL_TIMEOUT_MS - 300, metas: [meta] });

    expect(res.status).toBe(422);
    expect(mockPdf).not.toHaveBeenCalled();
  });
});

/**
 * meta refresh content 해석 단위 시험(WD-799).
 * 기대값은 HTML 표준 해석 규칙과 Chrome 153 실측(이동 요청 발생 여부·시점)에 맞춘다 —
 * 근거: .work-loop/state/evidence/WD-799/meta-refresh-chrome-probe.txt
 */
describe('isRefreshWithinRender', () => {
  const LIMIT = 50;

  it.each([
    // [content, 기대값, 이유]
    ['0;url=https://example.com/', true, '0초 이동(가장 흔한 공격 형태)'],
    ['0', true, 'URL 없는 0초 refresh — about:blank 다시 읽기로 빈 PDF 위험이 있어 거부 유지'],
    ['5', true, '렌더 기한 안의 지연'],
    [' 1.5 ; url=x', true, '앞 공백 무시, 정수 부분(1초)만 씀 — 실측 1초 뒤 이동'],
    ['\t\n2;url=x', true, 'ASCII 공백(탭·줄바꿈)도 건너뜀'],
    ['.5;url=x', true, '숫자 없이 . 로 시작하면 지연 0 — 실측 즉시 이동'],
    ['1,url=x', true, '쉼표 구분자도 유효 — 실측 1초 뒤 이동'],
    ['50', true, '경계값 — 기한(50초)과 같으면 거부'],
    ['50.9', true, '정수 부분 50 만 씀 → 거부'],
    ['1abc', true, '표준·Chrome 은 무시하지만 보수적으로 거부(이동이 새지 않는 쪽)'],
    ['51', false, '기한 뒤에야 일어남'],
    ['3600', false, '한 시간 뒤 — 렌더 중 일어나지 않음'],
    ['99999999999999999999999', false, '아주 긴 숫자 — 큰 값으로 읽혀 기한 밖'],
    ['', false, '빈 값 — Chrome 이 무시(실측 이동 없음)'],
    ['   ', false, '공백뿐 — 숫자 없음'],
    ['abc', false, '숫자로 시작하지 않음 — Chrome 이 무시(실측)'],
    ['-1;url=x', false, '부호는 숫자가 아님 — Chrome 이 무시(실측)'],
    ['+1;url=x', false, '부호는 숫자가 아님 — Chrome 이 무시(실측)'],
    [';url=x', false, '지연 없이 URL 만 — Chrome 이 무시(실측)'],
    ['\u00a01;url=x', false, 'NBSP 는 ASCII 공백이 아님 — Chrome 이 무시(실측)'],
  ])('%j → %s (%s)', (content, expected) => {
    expect(isRefreshWithinRender(content, LIMIT)).toBe(expected);
  });

  it('content 속성이 없으면(null) refresh 가 일어나지 않으므로 통과', () => {
    expect(isRefreshWithinRender(null, LIMIT)).toBe(false);
  });
});
