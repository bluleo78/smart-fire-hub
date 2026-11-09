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
  mockClose,
  mockNewPage,
  mockLaunch,
  mockSetRequestInterception,
  mockSetJavaScriptEnabled,
  mockOn,
  mockUrl,
  mockMainFrame,
} = vi.hoisted(() => {
  const mockPdf = vi.fn();
  const mockSetContent = vi.fn();
  const mockClose = vi.fn();
  // WD-617: 요청 가로채기·JS 비활성화 호출을 검증하기 위한 mock
  const mockSetRequestInterception = vi.fn();
  const mockSetJavaScriptEnabled = vi.fn();
  const mockOn = vi.fn();
  // WD-617: 메인 프레임 이동 감지용 — 기본은 이동 없이 about:blank 에 머문 상태
  const mockUrl = vi.fn();
  const mockMainFrame = {};
  const mockPage = {
    setContent: mockSetContent,
    pdf: mockPdf,
    close: mockClose,
    setRequestInterception: mockSetRequestInterception,
    setJavaScriptEnabled: mockSetJavaScriptEnabled,
    on: mockOn,
    url: mockUrl,
    mainFrame: () => mockMainFrame,
  };
  const mockNewPage = vi.fn().mockResolvedValue(mockPage);
  const mockBrowser = {
    newPage: mockNewPage,
    connected: true,
  };
  const mockLaunch = vi.fn().mockResolvedValue(mockBrowser);
  return {
    mockPdf,
    mockSetContent,
    mockClose,
    mockNewPage,
    mockLaunch,
    mockSetRequestInterception,
    mockSetJavaScriptEnabled,
    mockOn,
    mockUrl,
    mockMainFrame,
  };
});

// Puppeteer 모킹 — 실제 headless Chrome 실행 방지
vi.mock('puppeteer', () => ({
  default: {
    launch: mockLaunch,
  },
}));

// 모킹 설정 후 라우터를 동적으로 임포트
import pdfRouter from './pdf.js';

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
    const mockPage = {
      setContent: mockSetContent,
      pdf: mockPdf,
      close: mockClose,
      setRequestInterception: mockSetRequestInterception,
      setJavaScriptEnabled: mockSetJavaScriptEnabled,
      on: mockOn,
      url: mockUrl,
      mainFrame: () => mockMainFrame,
    };
    mockUrl.mockReturnValue('about:blank');
    mockNewPage.mockResolvedValue(mockPage);
    mockLaunch.mockResolvedValue({ newPage: mockNewPage, connected: true });
    mockSetContent.mockResolvedValue(undefined);
    mockClose.mockResolvedValue(undefined);
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
  });

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
    expect(mockPdf).toHaveBeenCalledOnce();
    // 페이지는 반드시 닫혀야 함 (finally 블록)
    expect(mockClose).toHaveBeenCalledOnce();
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
});
