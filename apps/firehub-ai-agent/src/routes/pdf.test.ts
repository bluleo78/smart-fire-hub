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
const { mockPdf, mockSetContent, mockClose, mockNewPage, mockLaunch } = vi.hoisted(() => {
  const mockPdf = vi.fn();
  const mockSetContent = vi.fn();
  const mockClose = vi.fn();
  const mockPage = {
    setContent: mockSetContent,
    pdf: mockPdf,
    close: mockClose,
  };
  const mockNewPage = vi.fn().mockResolvedValue(mockPage);
  const mockBrowser = {
    newPage: mockNewPage,
    connected: true,
  };
  const mockLaunch = vi.fn().mockResolvedValue(mockBrowser);
  return { mockPdf, mockSetContent, mockClose, mockNewPage, mockLaunch };
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
    };
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
});
