import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import express from 'express';
import classifyRouter from './classify.js';

const mockClassify = vi.fn();
const mockClassifyProvider = { name: 'mock-classify', classify: mockClassify };

vi.mock('../providers/index.js', () => ({
  ProviderFactory: {
    createClassifyProvider: vi.fn(() => mockClassifyProvider),
  },
}));

const VALID_TOKEN = 'test-internal-token-12345';

function createApp() {
  const app = express();
  app.use(express.json());
  app.use('/agent', classifyRouter);
  return app;
}

async function makeRequest(
  app: express.Express,
  path: string,
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
      const url = `http://localhost:${port}${path}`;
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

const validBody = {
  rows: [
    { id: 1, name: '홍길동', free_comment: '서비스가 좋았습니다' },
    { id: 2, name: '김철수', free_comment: '별로였어요' },
  ],
  prompt: '각 행의 free_comment를 감성 분류하세요',
  outputColumns: [
    { name: 'label', type: 'TEXT' },
    { name: 'confidence', type: 'DECIMAL' },
    { name: 'reason', type: 'TEXT' },
  ],
};

const mockResponse = {
  results: [
    { source_id: 1, label: '긍정', confidence: 0.95, reason: '만족 표현' },
    { source_id: 2, label: '부정', confidence: 0.88, reason: '불만 표현' },
  ],
  processed: 2,
  model: 'claude-haiku-4-5-20251001',
  usage: { promptTokens: 200, completionTokens: 100 },
};

describe('POST /agent/classify', () => {
  beforeEach(() => {
    process.env.INTERNAL_SERVICE_TOKEN = VALID_TOKEN;
    process.env.API_BASE_URL = 'http://localhost:8080/api/v1';
    vi.clearAllMocks();
  });

  afterEach(() => {
    delete process.env.INTERNAL_SERVICE_TOKEN;
    delete process.env.API_BASE_URL;
  });

  it('should return 401 when Authorization header is missing', async () => {
    const app = createApp();
    const res = await makeRequest(app, '/agent/classify', validBody);
    expect(res.status).toBe(401);
  });

  it('should return 401 for invalid Internal token', async () => {
    const app = createApp();
    const res = await makeRequest(app, '/agent/classify', validBody, {
      Authorization: 'Internal wrong-token',
    });
    expect(res.status).toBe(401);
  });

  it('should return 400 when rows is empty', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      '/agent/classify',
      { ...validBody, rows: [] },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });

  it('should return 400 when prompt is missing', async () => {
    const app = createApp();
    const { prompt: _prompt, ...bodyWithoutPrompt } = validBody;
    const res = await makeRequest(app, '/agent/classify', bodyWithoutPrompt as Record<string, unknown>, {
      Authorization: `Internal ${VALID_TOKEN}`,
    });
    expect(res.status).toBe(400);
  });

  it('should return 400 when outputColumns is missing', async () => {
    const app = createApp();
    const { outputColumns: _oc, ...bodyWithoutColumns } = validBody;
    const res = await makeRequest(app, '/agent/classify', bodyWithoutColumns as Record<string, unknown>, {
      Authorization: `Internal ${VALID_TOKEN}`,
    });
    expect(res.status).toBe(400);
  });

  it('should return 400 when outputColumns is empty', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      '/agent/classify',
      { ...validBody, outputColumns: [] },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty('error');
  });

  it('should return 400 when outputColumn has invalid type', async () => {
    const app = createApp();
    const res = await makeRequest(
      app,
      '/agent/classify',
      {
        ...validBody,
        outputColumns: [{ name: 'label', type: 'INVALID_TYPE' }],
      },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );
    expect(res.status).toBe(400);
  });

  it('should classify successfully with valid request', async () => {
    const { ProviderFactory } = await import('../providers/index.js');
    mockClassify.mockResolvedValue(mockResponse);

    const app = createApp();
    const res = await makeRequest(app, '/agent/classify', validBody, {
      Authorization: `Internal ${VALID_TOKEN}`,
    });

    expect(res.status).toBe(200);
    expect(res.body).toEqual(mockResponse);
    expect(ProviderFactory.createClassifyProvider).toHaveBeenCalledOnce();
    expect(mockClassify).toHaveBeenCalledOnce();
    expect(mockClassify).toHaveBeenCalledWith(
      expect.objectContaining({
        rows: validBody.rows,
        prompt: validBody.prompt,
        outputColumns: validBody.outputColumns,
      }),
    );
  });

  it('should return 500 when classification service throws', async () => {
    mockClassify.mockRejectedValue(new Error('AI API key is not configured'));

    const app = createApp();
    const res = await makeRequest(app, '/agent/classify', validBody, {
      Authorization: `Internal ${VALID_TOKEN}`,
    });

    expect(res.status).toBe(500);
    expect(res.body).toHaveProperty('error');
  });

  it('should accept rows with free-form object structure', async () => {
    mockClassify.mockResolvedValue({
      ...mockResponse,
      results: [{ source_id: 1, label: '중립', confidence: 0.5, reason: '판단 불가' }],
      processed: 1,
    });

    const app = createApp();
    const res = await makeRequest(
      app,
      '/agent/classify',
      { ...validBody, rows: [{ id: 1, col_a: 'value', col_b: 42, extra: true }] },
      { Authorization: `Internal ${VALID_TOKEN}` },
    );

    expect(res.status).toBe(200);
  });
});
