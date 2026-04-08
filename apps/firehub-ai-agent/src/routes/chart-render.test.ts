/**
 * 차트 렌더링 라우트 통합 테스트
 *
 * POST /agent/chart-render 엔드포인트에 대한 테스트.
 * ChartJSNodeCanvas는 vi.mock으로 대체하여 실제 Canvas 렌더링을 수행하지 않는다.
 *
 * 주의: vi.mock 팩토리는 호이스팅되므로 외부 변수를 참조할 수 없다.
 * vi.hoisted()로 mock 함수를 미리 선언하고, ChartJSNodeCanvas를 class로 모킹해야 한다.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';

// vi.hoisted()로 mock 함수를 팩토리 호이스팅 전에 선언
const { mockRenderToBuffer } = vi.hoisted(() => {
  const mockRenderToBuffer = vi.fn();
  return { mockRenderToBuffer };
});

// ChartJSNodeCanvas 모킹 — class로 모킹하여 new 연산자 지원
vi.mock('chartjs-node-canvas', () => ({
  ChartJSNodeCanvas: class {
    renderToBuffer = mockRenderToBuffer;
  },
}));

// 모킹 설정 후 라우터를 임포트
import chartRenderRouter from './chart-render.js';

const VALID_TOKEN = 'test-internal-token-chart';

/** Express 앱 생성 헬퍼 */
function createApp() {
  const app = express();
  app.use('/agent', chartRenderRouter);
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
): Promise<{ status: number; body: unknown }> {
  return new Promise((resolve, reject) => {
    const server = app.listen(0, () => {
      const addr = server.address();
      if (!addr || typeof addr === 'string') {
        server.close();
        reject(new Error('Failed to get server address'));
        return;
      }
      const port = addr.port;
      const url = `http://localhost:${port}/agent/chart-render`;
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
          const text = await res.text();
          let parsed: unknown;
          try {
            parsed = JSON.parse(text);
          } catch {
            parsed = text;
          }
          server.close();
          resolve({ status: res.status, body: parsed });
        })
        .catch((err) => {
          server.close();
          reject(err);
        });
    });
  });
}

/** 유효한 차트 요청 바디 */
const validBody = {
  charts: [
    {
      type: 'bar',
      title: '월별 매출',
      data: {
        labels: ['1월', '2월', '3월'],
        datasets: [
          {
            label: '매출',
            data: [100, 200, 150],
            backgroundColor: '#4e79a7',
          },
        ],
      },
      width: 600,
      height: 400,
    },
  ],
};

describe('POST /agent/chart-render', () => {
  beforeEach(() => {
    // 각 테스트 전 환경변수 설정 및 mock 초기화
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
    vi.clearAllMocks();
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
  });

  // 시나리오 1: Authorization 헤더 누락 → 401
  it('should return 401 when Authorization header is missing', async () => {
    const app = createApp();
    const res = await makeRequest(app, validBody);
    expect(res.status).toBe(401);
  });

  // 시나리오 2: 잘못된 Internal 토큰 → 401
  it('should return 401 for invalid Internal token', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      validBody,
      { Authorization: 'Internal wrong-token' },
    );
    expect(res.status).toBe(401);
  });

  // 시나리오 3: charts 필드 누락 → 400
  it('should return 400 when charts field is missing', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      {},
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });

  // 시나리오 4: charts가 빈 배열 → 400
  it('should return 400 when charts array is empty', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      { charts: [] },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error', 'charts array is required and must not be empty');
  });

  // 시나리오 5: charts가 배열이 아님 → 400
  it('should return 400 when charts is not an array', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      { charts: 'not-an-array' },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });

  // 시나리오 6: 정상 요청 → 200 + images 배열
  it('should return 200 with images array on valid request', async () => {
    // 가짜 PNG 버퍼 반환
    const fakePngBuffer = Buffer.from('fake-png-data');
    mockRenderToBuffer.mockResolvedValue(fakePngBuffer);

    const app = createApp();
    const res = await makeRequest(
      app,
      validBody,
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    const body = res.body as { images: Array<{ id: string; base64: string; mimeType: string }> };
    expect(body).toHaveProperty('images');
    expect(Array.isArray(body.images)).toBe(true);
    expect(body.images).toHaveLength(1);
    expect(body.images[0]).toHaveProperty('id', 'chart-0');
    expect(body.images[0]).toHaveProperty('mimeType', 'image/png');
    expect(body.images[0]).toHaveProperty('base64', fakePngBuffer.toString('base64'));
    expect(mockRenderToBuffer).toHaveBeenCalledOnce();
  });

  // 시나리오 7: 여러 차트 한번에 렌더링
  it('should render multiple charts and return images for each', async () => {
    const fakePngBuffer = Buffer.from('fake-png');
    mockRenderToBuffer.mockResolvedValue(fakePngBuffer);

    const multiChartBody = {
      charts: [
        {
          type: 'bar',
          title: '차트1',
          data: { labels: ['A', 'B'], datasets: [{ data: [1, 2] }] },
        },
        {
          type: 'pie',
          title: '차트2',
          data: { labels: ['X', 'Y'], datasets: [{ data: [30, 70] }] },
        },
      ],
    };

    const app = createApp();
    const res = await makeRequest(
      app,
      multiChartBody,
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
    const body = res.body as { images: Array<{ id: string }> };
    expect(body.images).toHaveLength(2);
    expect(body.images[0].id).toBe('chart-0');
    expect(body.images[1].id).toBe('chart-1');
    expect(mockRenderToBuffer).toHaveBeenCalledTimes(2);
  });

  // 시나리오 8: renderToBuffer 오류 → 500
  it('should return 500 when renderToBuffer throws an error', async () => {
    mockRenderToBuffer.mockRejectedValue(new Error('Canvas rendering failed'));

    const app = createApp();
    const res = await makeRequest(
      app,
      validBody,
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(500);
    expect(res.body).toHaveProperty('error', 'Chart rendering failed');
    expect(res.body).toHaveProperty('details');
  });
});
